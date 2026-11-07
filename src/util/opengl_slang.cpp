// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Slang presets under OpenGL and OpenGL ES: librashader's OpenGL runtime on the device's context. Like the other
// runtimes, it takes texel row 0 as the picture's top (its own MVP flips for OpenGL).

#include "opengl_device.h"
#include "opengl_texture.h"

#define LIBRA_RUNTIME_OPENGL
#include "librashader_loader.h"

#include "common/error.h"

class OpenGLSlangChain final : public SlangChain
{
public:
  ~OpenGLSlangChain() override;

  bool Create(const std::string& preset_path, Error* error);

  void SetParameter(const char* name, float value) override;
  bool Frame(GPUTexture* input, GPUTexture* output, u64 frame_count, Error* error) override;

private:
  static const void* LoadFunction(const char* name);

  PFN_libra_gl_filter_chain_create m_create = nullptr;
  PFN_libra_gl_filter_chain_frame m_frame = nullptr;
  PFN_libra_gl_filter_chain_set_param m_set_param = nullptr;
  PFN_libra_gl_filter_chain_free m_free = nullptr;
  libra_gl_filter_chain_t m_chain = nullptr;
};

OpenGLSlangChain::~OpenGLSlangChain()
{
  if (!m_chain)
    return;

  if (libra_error_t err = m_free(&m_chain))
    Librashader::DescribeAndFreeError(err);
  OpenGLDevice::GetInstance().InvalidateCachedState();
}

const void* OpenGLSlangChain::LoadFunction(const char* name)
{
  return OpenGLDevice::GetInstance().m_gl_context->GetProcAddress(name);
}

bool OpenGLSlangChain::Create(const std::string& preset_path, Error* error)
{
  if (!Librashader::ResolveSymbol("libra_gl_filter_chain_create", &m_create, error) ||
      !Librashader::ResolveSymbol("libra_gl_filter_chain_frame", &m_frame, error) ||
      !Librashader::ResolveSymbol("libra_gl_filter_chain_set_param", &m_set_param, error) ||
      !Librashader::ResolveSymbol("libra_gl_filter_chain_free", &m_free, error))
  {
    return false;
  }

  libra_shader_preset_t preset = nullptr;
  if (!Librashader::LoadPreset(preset_path, LIBRA_PRESET_CTX_RUNTIME_GL_CORE, &preset, error))
    return false;

  filter_chain_gl_opt_t opt = {};
  opt.version = LIBRASHADER_CURRENT_VERSION;
  opt.glsl_version = 0;  // librashader picks it from the context, GLSL ES included
  opt.use_dsa = false;   // GL 4.5 only, and not in GL ES
  opt.force_no_mipmaps = false;
  opt.disable_cache = false;

  // The preset is consumed, even when creating the chain fails. Creating it uploads LUTs, binding textures and buffers
  // of its own.
  const libra_error_t err = m_create(&preset, &LoadFunction, &opt, &m_chain);
  OpenGLDevice::GetInstance().InvalidateCachedState();
  if (err)
  {
    Error::SetString(error, Librashader::DescribeAndFreeError(err));
    m_chain = nullptr;
    return false;
  }

  return true;
}

void OpenGLSlangChain::SetParameter(const char* name, float value)
{
  if (libra_error_t err = m_set_param(&m_chain, name, value))
    Librashader::DescribeAndFreeError(err); // a parameter the preset doesn't have
}

bool OpenGLSlangChain::Frame(GPUTexture* input, GPUTexture* output, u64 frame_count, Error* error)
{
  OpenGLDevice& dev = OpenGLDevice::GetInstance();
  OpenGLTexture* const src = static_cast<OpenGLTexture*>(input);
  OpenGLTexture* const dst = static_cast<OpenGLTexture*>(output);
  dev.CommitClear(src);
  dev.CommitClear(dst);

  // The stage's own textures, always RGBA8.
  const libra_image_gl_t in = {src->GetGLId(), GL_RGBA8, src->GetWidth(), src->GetHeight()};
  const libra_image_gl_t out = {dst->GetGLId(), GL_RGBA8, dst->GetWidth(), dst->GetHeight()};
  const libra_viewport_t vp = {0.0f, 0.0f, dst->GetWidth(), dst->GetHeight()};
  const libra_error_t err = m_frame(&m_chain, static_cast<size_t>(frame_count), in, out, &vp, nullptr, nullptr);

  // librashader binds framebuffers, programs, vertex arrays, textures and samplers of its own.
  dev.InvalidateCachedState();

  if (err)
  {
    Error::SetString(error, Librashader::DescribeAndFreeError(err));
    return false;
  }

  return true;
}

bool OpenGLDevice::SupportsSlangPresets() const
{
  return true;
}

std::unique_ptr<SlangChain> OpenGLDevice::CreateSlangChain(const std::string& preset_path, Error* error)
{
  std::unique_ptr<OpenGLSlangChain> chain = std::make_unique<OpenGLSlangChain>();
  if (!chain->Create(preset_path, error))
    return {};
  return chain;
}
