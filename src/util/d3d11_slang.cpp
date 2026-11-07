// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Slang presets under D3D11: librashader's D3D11 runtime on the immediate context. Adapted from the PCSX2 fork's
// GSDevice11 glue.

#include "d3d11_device.h"
#include "d3d11_texture.h"

#define LIBRA_RUNTIME_D3D11
#include "librashader_loader.h"

#include "common/error.h"

class D3D11SlangChain final : public SlangChain
{
public:
  ~D3D11SlangChain() override;

  bool Create(const std::string& preset_path, Error* error);

  void SetParameter(const char* name, float value) override;
  bool Frame(GPUTexture* input, GPUTexture* output, u64 frame_count, Error* error) override;

private:
  PFN_libra_d3d11_filter_chain_create m_create = nullptr;
  PFN_libra_d3d11_filter_chain_frame m_frame = nullptr;
  PFN_libra_d3d11_filter_chain_set_param m_set_param = nullptr;
  PFN_libra_d3d11_filter_chain_free m_free = nullptr;
  libra_d3d11_filter_chain_t m_chain = nullptr;
};

D3D11SlangChain::~D3D11SlangChain()
{
  if (m_chain)
  {
    if (libra_error_t err = m_free(&m_chain))
      Librashader::DescribeAndFreeError(err);
  }
}

bool D3D11SlangChain::Create(const std::string& preset_path, Error* error)
{
  if (!Librashader::ResolveSymbol("libra_d3d11_filter_chain_create", &m_create, error) ||
      !Librashader::ResolveSymbol("libra_d3d11_filter_chain_frame", &m_frame, error) ||
      !Librashader::ResolveSymbol("libra_d3d11_filter_chain_set_param", &m_set_param, error) ||
      !Librashader::ResolveSymbol("libra_d3d11_filter_chain_free", &m_free, error))
  {
    return false;
  }

  libra_shader_preset_t preset = nullptr;
  if (!Librashader::LoadPreset(preset_path, LIBRA_PRESET_CTX_RUNTIME_D3D11, &preset, error))
    return false;

  filter_chain_d3d11_opt_t opt = {};
  opt.version = LIBRASHADER_CURRENT_VERSION;
  opt.force_no_mipmaps = false;
  opt.disable_cache = false;

  // The preset is consumed, even when creating the chain fails.
  if (libra_error_t err = m_create(&preset, D3D11Device::GetD3DDevice(), &opt, &m_chain))
  {
    Error::SetString(error, Librashader::DescribeAndFreeError(err));
    m_chain = nullptr;
    return false;
  }

  return true;
}

void D3D11SlangChain::SetParameter(const char* name, float value)
{
  if (libra_error_t err = m_set_param(&m_chain, name, value))
    Librashader::DescribeAndFreeError(err); // a parameter the preset doesn't have
}

bool D3D11SlangChain::Frame(GPUTexture* input, GPUTexture* output, u64 frame_count, Error* error)
{
  D3D11Device& dev = D3D11Device::GetInstance();
  ID3D11DeviceContext1* const context = dev.m_context.Get();
  D3D11Texture* const src = static_cast<D3D11Texture*>(input);
  D3D11Texture* const dst = static_cast<D3D11Texture*>(output);
  src->CommitClear(context);
  dst->CommitClear(context);

  // The input mustn't be bound as a render target, nor the output as a shader resource.
  context->OMSetRenderTargets(0, nullptr, nullptr);
  ID3D11ShaderResourceView* const null_srvs[D3D11Device::MAX_TEXTURE_SAMPLERS] = {};
  context->PSSetShaderResources(0, D3D11Device::MAX_TEXTURE_SAMPLERS, null_srvs);

  const libra_viewport_t vp = {0.0f, 0.0f, dst->GetWidth(), dst->GetHeight()};
  const libra_error_t err = m_frame(&m_chain, nullptr /* the immediate context */, static_cast<size_t>(frame_count),
                                    src->GetD3DSRV(), dst->GetD3DRTV(), &vp, nullptr, nullptr);

  // librashader binds shaders, states, buffers and views of its own.
  dev.InvalidateCachedState();

  if (err)
  {
    Error::SetString(error, Librashader::DescribeAndFreeError(err));
    return false;
  }

  return true;
}

bool D3D11Device::SupportsSlangPresets() const
{
  return true;
}

std::unique_ptr<SlangChain> D3D11Device::CreateSlangChain(const std::string& preset_path, Error* error)
{
  std::unique_ptr<D3D11SlangChain> chain = std::make_unique<D3D11SlangChain>();
  if (!chain->Create(preset_path, error))
    return {};
  return chain;
}
