// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Slang presets under Metal: librashader's Metal runtime on the device's queue and command buffer. Adapted from the
// PCSX2 fork's GSDeviceMTL glue.

#define LIBRA_RUNTIME_METAL
#include "librashader_loader.h"
#include "metal_device.h"

#include "common/error.h"

class MetalSlangChain final : public SlangChain
{
public:
  ~MetalSlangChain() override;

  bool Create(const std::string& preset_path, Error* error);

  void SetParameter(const char* name, float value) override;
  bool Frame(GPUTexture* input, GPUTexture* output, u64 frame_count, Error* error) override;

private:
  PFN_libra_mtl_filter_chain_create m_create = nullptr;
  PFN_libra_mtl_filter_chain_frame m_frame = nullptr;
  PFN_libra_mtl_filter_chain_set_param m_set_param = nullptr;
  PFN_libra_mtl_filter_chain_free m_free = nullptr;
  libra_mtl_filter_chain_t m_chain = nullptr;
};

MetalSlangChain::~MetalSlangChain()
{
  if (!m_chain)
    return;

  // Nothing in flight may still use the chain's resources.
  MetalDevice& dev = MetalDevice::GetInstance();
  dev.SubmitCommandBuffer(true);
  if (libra_error_t err = m_free(&m_chain))
    Librashader::DescribeAndFreeError(err);
}

bool MetalSlangChain::Create(const std::string& preset_path, Error* error)
{
  if (!Librashader::ResolveSymbol("libra_mtl_filter_chain_create", &m_create, error) ||
      !Librashader::ResolveSymbol("libra_mtl_filter_chain_frame", &m_frame, error) ||
      !Librashader::ResolveSymbol("libra_mtl_filter_chain_set_param", &m_set_param, error) ||
      !Librashader::ResolveSymbol("libra_mtl_filter_chain_free", &m_free, error))
  {
    return false;
  }

  libra_shader_preset_t preset = nullptr;
  if (!Librashader::LoadPreset(preset_path, LIBRA_PRESET_CTX_RUNTIME_METAL, &preset, error))
    return false;

  @autoreleasepool
  {
    MetalDevice& dev = MetalDevice::GetInstance();
    filter_chain_mtl_opt_t opt = {};
    opt.version = LIBRASHADER_CURRENT_VERSION;
    opt.force_no_mipmaps = false;

    // The preset is consumed, even when creating the chain fails.
    if (libra_error_t err = m_create(&preset, dev.m_queue, &opt, &m_chain))
    {
      Error::SetString(error, Librashader::DescribeAndFreeError(err));
      m_chain = nullptr;
      return false;
    }
  }

  return true;
}

void MetalSlangChain::SetParameter(const char* name, float value)
{
  if (libra_error_t err = m_set_param(&m_chain, name, value))
    Librashader::DescribeAndFreeError(err); // a parameter the preset doesn't have
}

bool MetalSlangChain::Frame(GPUTexture* input, GPUTexture* output, u64 frame_count, Error* error)
{
  @autoreleasepool
  {
    MetalDevice& dev = MetalDevice::GetInstance();

    // librashader opens encoders of its own on the command buffer: ours must be closed.
    dev.EndAnyEncoding();

    const libra_viewport_t vp = {0.0f, 0.0f, output->GetWidth(), output->GetHeight()};
    const libra_error_t err =
      m_frame(&m_chain, dev.m_render_cmdbuf, static_cast<size_t>(frame_count),
              static_cast<MetalTexture*>(input)->GetMTLTexture(), static_cast<MetalTexture*>(output)->GetMTLTexture(),
              &vp, nullptr, nullptr);

    // librashader recycles its per-frame resources on a ring of its own, so each of its frames ends a batch.
    dev.SubmitCommandBuffer(false);

    if (err)
    {
      Error::SetString(error, Librashader::DescribeAndFreeError(err));
      return false;
    }
  }

  return true;
}

bool MetalDevice::SupportsSlangPresets() const
{
  return true;
}

std::unique_ptr<SlangChain> MetalDevice::CreateSlangChain(const std::string& preset_path, Error* error)
{
  std::unique_ptr<MetalSlangChain> chain = std::make_unique<MetalSlangChain>();
  if (!chain->Create(preset_path, error))
    return {};
  return chain;
}
