// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Slang presets under D3D12: librashader's D3D12 runtime records into the device's current command list. It compiles
// with DXC, so dxcompiler.dll and dxil.dll ship beside the program. Adapted from the PCSX2 fork's GSDevice12 glue.

#include "d3d12_device.h"
#include "d3d12_texture.h"

#define LIBRA_RUNTIME_D3D12
#include "librashader_loader.h"

#include "common/error.h"

class D3D12SlangChain final : public SlangChain
{
public:
  ~D3D12SlangChain() override;

  bool Create(const std::string& preset_path, Error* error);

  void SetParameter(const char* name, float value) override;
  bool Frame(GPUTexture* input, GPUTexture* output, u64 frame_count, Error* error) override;

private:
  PFN_libra_d3d12_filter_chain_create m_create = nullptr;
  PFN_libra_d3d12_filter_chain_frame m_frame = nullptr;
  PFN_libra_d3d12_filter_chain_set_param m_set_param = nullptr;
  PFN_libra_d3d12_filter_chain_free m_free = nullptr;
  libra_d3d12_filter_chain_t m_chain = nullptr;
};

D3D12SlangChain::~D3D12SlangChain()
{
  if (!m_chain)
    return;

  // The chain owns per-frame D3D12 objects: nothing in flight may still use them.
  D3D12Device::GetInstance().ExecuteAndWaitForGPUIdle();
  if (libra_error_t err = m_free(&m_chain))
    Librashader::DescribeAndFreeError(err);
}

bool D3D12SlangChain::Create(const std::string& preset_path, Error* error)
{
  if (!Librashader::ResolveSymbol("libra_d3d12_filter_chain_create", &m_create, error) ||
      !Librashader::ResolveSymbol("libra_d3d12_filter_chain_frame", &m_frame, error) ||
      !Librashader::ResolveSymbol("libra_d3d12_filter_chain_set_param", &m_set_param, error) ||
      !Librashader::ResolveSymbol("libra_d3d12_filter_chain_free", &m_free, error))
  {
    return false;
  }

  libra_shader_preset_t preset = nullptr;
  if (!Librashader::LoadPreset(preset_path, LIBRA_PRESET_CTX_RUNTIME_D3D12, &preset, error))
    return false;

  filter_chain_d3d12_opt_t opt = {};
  opt.version = LIBRASHADER_CURRENT_VERSION;
  opt.force_hlsl_pipeline = false;
  opt.force_no_mipmaps = false;
  opt.disable_cache = false;
  opt.frames_in_flight = D3D12Device::NUM_COMMAND_LISTS;

  // The preset is consumed, even when creating the chain fails.
  if (libra_error_t err = m_create(&preset, D3D12Device::GetInstance().GetDevice(), &opt, &m_chain))
  {
    Error::SetString(error, Librashader::DescribeAndFreeError(err));
    m_chain = nullptr;
    return false;
  }

  return true;
}

void D3D12SlangChain::SetParameter(const char* name, float value)
{
  if (libra_error_t err = m_set_param(&m_chain, name, value))
    Librashader::DescribeAndFreeError(err); // a parameter the preset doesn't have
}

bool D3D12SlangChain::Frame(GPUTexture* input, GPUTexture* output, u64 frame_count, Error* error)
{
  D3D12Device& dev = D3D12Device::GetInstance();
  D3D12Texture* const src = static_cast<D3D12Texture*>(input);
  D3D12Texture* const dst = static_cast<D3D12Texture*>(output);

  // librashader records render passes of its own: ours must be closed.
  if (dev.InRenderPass())
    dev.EndRenderPass();
  src->CommitClear();
  dst->CommitClear();
  src->TransitionToState(D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);
  dst->TransitionToState(D3D12_RESOURCE_STATE_RENDER_TARGET);

  libra_image_d3d12_t in = {};
  in.image_type = LIBRA_D3D12_IMAGE_TYPE_RESOURCE;
  in.handle.resource = src->GetResource();
  libra_image_d3d12_t out = {};
  out.image_type = LIBRA_D3D12_IMAGE_TYPE_RESOURCE;
  out.handle.resource = dst->GetResource();
  const libra_viewport_t vp = {0.0f, 0.0f, dst->GetWidth(), dst->GetHeight()};

  ID3D12GraphicsCommandList4* const cmdlist = dev.GetCommandList();
  const libra_error_t err =
    m_frame(&m_chain, cmdlist, static_cast<size_t>(frame_count), in, out, &vp, nullptr, nullptr);

  // librashader bound descriptor heaps, a root signature and a pipeline of its own. The device sets its heaps only
  // when a command list starts, so they're bound again here.
  dev.InvalidateCachedState();
  D3D12Device::CommandList& res = dev.m_command_lists[dev.m_current_command_list];
  ID3D12DescriptorHeap* heaps[2] = {res.descriptor_allocator.GetDescriptorHeap(),
                                    res.sampler_allocator.GetDescriptorHeap()};
  cmdlist->SetDescriptorHeaps(static_cast<UINT>(std::size(heaps)), heaps);

  if (err)
  {
    Error::SetString(error, Librashader::DescribeAndFreeError(err));
    return false;
  }

  return true;
}

bool D3D12Device::SupportsSlangPresets() const
{
  return true;
}

std::unique_ptr<SlangChain> D3D12Device::CreateSlangChain(const std::string& preset_path, Error* error)
{
  std::unique_ptr<D3D12SlangChain> chain = std::make_unique<D3D12SlangChain>();
  if (!chain->Create(preset_path, error))
    return {};
  return chain;
}
