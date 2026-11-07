// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Slang presets under Vulkan: librashader's Vulkan runtime records into the device's current command buffer. Adapted
// from the PCSX2 fork's GSDeviceVK glue.

#include "vulkan_device.h"
#include "vulkan_texture.h"

#define LIBRA_RUNTIME_VULKAN
#include "librashader_loader.h"

#include "common/error.h"

#include <optional>

class VulkanSlangChain final : public SlangChain
{
public:
  ~VulkanSlangChain() override;

  bool Create(const std::string& preset_path, Error* error);

  void SetParameter(const char* name, float value) override;
  bool Frame(GPUTexture* input, GPUTexture* output, u64 frame_count, Error* error) override;

private:
  PFN_libra_vk_filter_chain_create m_create = nullptr;
  PFN_libra_vk_filter_chain_frame m_frame = nullptr;
  PFN_libra_vk_filter_chain_set_param m_set_param = nullptr;
  PFN_libra_vk_filter_chain_free m_free = nullptr;
  libra_vk_filter_chain_t m_chain = nullptr;

  // The command buffer of the last frame. librashader reuses a frame's temporary objects frames_in_flight frames
  // later, which is only safe when each command buffer has one frame of the chain (screenshots and video capture
  // render the display again before it's presented).
  std::optional<u64> m_last_frame_fence;
};

VulkanSlangChain::~VulkanSlangChain()
{
  if (!m_chain)
    return;

  // The chain owns per-frame Vulkan objects: nothing in flight may still use them.
  VulkanDevice::GetInstance().ExecuteAndWaitForGPUIdle();
  if (libra_error_t err = m_free(&m_chain))
    Librashader::DescribeAndFreeError(err);
}

bool VulkanSlangChain::Create(const std::string& preset_path, Error* error)
{
  if (!Librashader::ResolveSymbol("libra_vk_filter_chain_create", &m_create, error) ||
      !Librashader::ResolveSymbol("libra_vk_filter_chain_frame", &m_frame, error) ||
      !Librashader::ResolveSymbol("libra_vk_filter_chain_set_param", &m_set_param, error) ||
      !Librashader::ResolveSymbol("libra_vk_filter_chain_free", &m_free, error))
  {
    return false;
  }

  libra_shader_preset_t preset = nullptr;
  if (!Librashader::LoadPreset(preset_path, LIBRA_PRESET_CTX_RUNTIME_VULKAN, &preset, error))
    return false;

  VulkanDevice& dev = VulkanDevice::GetInstance();

  // Creating the chain submits its own uploads (LUTs) on the graphics queue, which must not race ours.
  dev.ExecuteAndWaitForGPUIdle();

  libra_device_vk_t vk = {};
  vk.physical_device = dev.m_physical_device;
  vk.instance = dev.m_instance;
  vk.device = dev.m_device;
  vk.queue = dev.m_graphics_queue;
  vk.entry = vkGetInstanceProcAddr;

  filter_chain_vk_opt_t opt = {};
  opt.version = LIBRASHADER_CURRENT_VERSION;
  opt.frames_in_flight = VulkanDevice::NUM_COMMAND_BUFFERS;
  opt.force_no_mipmaps = false;
  opt.use_dynamic_rendering = false;
  opt.disable_cache = false;

  // The preset is consumed, even when creating the chain fails.
  if (libra_error_t err = m_create(&preset, vk, &opt, &m_chain))
  {
    Error::SetString(error, Librashader::DescribeAndFreeError(err));
    m_chain = nullptr;
    return false;
  }

  return true;
}

void VulkanSlangChain::SetParameter(const char* name, float value)
{
  if (libra_error_t err = m_set_param(&m_chain, name, value))
    Librashader::DescribeAndFreeError(err); // a parameter the preset doesn't have
}

bool VulkanSlangChain::Frame(GPUTexture* input, GPUTexture* output, u64 frame_count, Error* error)
{
  VulkanDevice& dev = VulkanDevice::GetInstance();
  VulkanTexture* const src = static_cast<VulkanTexture*>(input);
  VulkanTexture* const dst = static_cast<VulkanTexture*>(output);

  if (m_last_frame_fence == dev.GetCurrentFenceCounter())
    dev.SubmitCommandBuffer(false, "Second slang frame in a command buffer");

  // librashader records render passes of its own into our command buffer.
  if (dev.InRenderPass())
    dev.EndRenderPass();
  src->CommitClear();
  dst->CommitClear();
  src->TransitionToLayout(VulkanTexture::Layout::ShaderReadOnly);
  dst->TransitionToLayout(VulkanTexture::Layout::ColorAttachment);

  const libra_image_vk_t in = {src->GetImage(), src->GetVkFormat(), src->GetWidth(), src->GetHeight()};
  const libra_image_vk_t out = {dst->GetImage(), dst->GetVkFormat(), dst->GetWidth(), dst->GetHeight()};
  const libra_viewport_t vp = {0.0f, 0.0f, dst->GetWidth(), dst->GetHeight()};
  const libra_error_t err =
    m_frame(&m_chain, dev.GetCurrentCommandBuffer(), static_cast<size_t>(frame_count), in, out, &vp, nullptr, nullptr);
  m_last_frame_fence = dev.GetCurrentFenceCounter();

  // librashader leaves the output as a colour attachment, with pipelines and descriptor sets of its own bound.
  dst->OverrideImageLayout(VulkanTexture::Layout::ColorAttachment);
  dst->TransitionToLayout(VulkanTexture::Layout::ShaderReadOnly);
  dev.InvalidateCachedState();

  if (err)
  {
    Error::SetString(error, Librashader::DescribeAndFreeError(err));
    return false;
  }

  return true;
}

bool VulkanDevice::SupportsSlangPresets() const
{
  return true;
}

std::unique_ptr<SlangChain> VulkanDevice::CreateSlangChain(const std::string& preset_path, Error* error)
{
  std::unique_ptr<VulkanSlangChain> chain = std::make_unique<VulkanSlangChain>();
  if (!chain->Create(preset_path, error))
    return {};
  return chain;
}
