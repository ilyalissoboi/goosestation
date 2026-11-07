// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "postprocessing_shader_slang.h"
#include "gpu_device.h"
#include "host.h"
#include "imgui_manager.h"
#include "librashader_loader.h"
#include "postprocessing_slang_picture.h"
#include "shadergen.h"

#include "IconsFontAwesome5.h"
#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/path.h"
#include "common/string_util.h"

#include "fmt/format.h"

#include <algorithm>
#include <cstring>

Log_SetChannel(PostProcessing);

namespace PostProcessing::Slang {

static constexpr std::string_view PRESET_EXTENSION = ".slangp";

// Splits on '/' and '\'.
static std::vector<std::string_view> SplitComponents(std::string_view path)
{
  std::vector<std::string_view> ret;
  size_t start = 0;
  while (start <= path.size())
  {
    size_t end = path.find_first_of("/\\", start);
    if (end == std::string_view::npos)
      end = path.size();
    ret.push_back(path.substr(start, end - start));
    start = end + 1;
  }
  return ret;
}

} // namespace PostProcessing::Slang

bool PostProcessing::Slang::IsPresetName(std::string_view name)
{
  return name.size() > PRESET_EXTENSION.size() &&
         StringUtil::EqualNoCase(name.substr(name.size() - PRESET_EXTENSION.size()), PRESET_EXTENSION);
}

std::string PostProcessing::Slang::ResolvePresetPath(std::string_view shaders_root, std::string_view name)
{
  if (name.empty() || name[0] == '/' || name[0] == '\\' || (name.size() >= 2 && name[1] == ':'))
    return {};

  std::string relative;
  for (const std::string_view component : SplitComponents(name))
  {
    if (component == "..")
      return {};
    if (!relative.empty())
      relative.push_back('/');
    relative.append(component);
  }

  return Path::Combine(shaders_root, relative);
}

std::vector<std::string> PostProcessing::Slang::FindPresets(std::string_view shaders_root)
{
  std::vector<std::string> ret;

  FileSystem::FindResultsArray results;
  FileSystem::FindFiles(std::string(shaders_root).c_str(), "*.slangp",
                        FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_RECURSIVE | FILESYSTEM_FIND_RELATIVE_PATHS, &results);

  for (FILESYSTEM_FIND_DATA& fd : results)
  {
    const std::vector<std::string_view> components = SplitComponents(fd.FileName);
    if (std::any_of(components.begin(), components.end(), [](std::string_view c) {
          return c.empty() || c[0] == '.' || c == "__MACOSX";
        }))
    {
      continue;
    }

    std::string name = std::move(fd.FileName);
#ifdef _WIN32
    // Stage names use '/' on every platform, so a chain from Windows works elsewhere.
    StringUtil::ReplaceAll(&name, '\\', '/');
#endif
    ret.push_back(std::move(name));
  }

  std::sort(ret.begin(), ret.end());
  return ret;
}

PostProcessing::SlangShader::SlangShader() = default;

PostProcessing::SlangShader::~SlangShader()
{
  // The chain's GPU objects go before the textures it was given.
  m_chain.reset();
  if (g_gpu_device)
  {
    g_gpu_device->RecycleTexture(std::move(m_result));
    g_gpu_device->RecycleTexture(std::move(m_native));
  }
}

bool PostProcessing::SlangShader::IsValid() const
{
  return !m_path.empty();
}

bool PostProcessing::SlangShader::WantsDepthBuffer() const
{
  return false;
}

bool PostProcessing::SlangShader::ReadsGamePicture() const
{
  return true;
}

bool PostProcessing::SlangShader::LoadFromFile(std::string name, std::string path, bool only_config, Error* error)
{
  libra_shader_preset_t preset = nullptr;
  if (!Librashader::LoadPreset(path, LIBRA_PRESET_CTX_RUNTIME_NONE, &preset, error))
    return false;

  const Librashader::CommonFunctions& c = Librashader::Common();
  libra_preset_param_list_t list = {};
  if (libra_error_t err = c.preset_get_runtime_params(&preset, &list))
  {
    Error::SetString(error, Librashader::DescribeAndFreeError(err));
    c.preset_free(&preset);
    return false;
  }

  m_options.clear();
  m_options.reserve(static_cast<size_t>(list.length));
  for (u64 i = 0; i < list.length; i++)
  {
    const libra_preset_param_t& p = list.parameters[i];
    ShaderOption opt = {};
    opt.name = p.name ? p.name : "";
    opt.ui_name = (p.description && p.description[0]) ? p.description : opt.name;
    opt.type = ShaderOption::Type::Float;
    opt.vector_size = 1;
    opt.default_value = ShaderOption::MakeFloatVector(p.initial);
    opt.min_value = ShaderOption::MakeFloatVector(p.minimum);
    opt.max_value = ShaderOption::MakeFloatVector(p.maximum);
    opt.step_value = ShaderOption::MakeFloatVector(p.step);
    opt.value = opt.default_value;
    if (!opt.name.empty())
      m_options.push_back(std::move(opt));
  }

  if (libra_error_t err = c.preset_free_runtime_params(list))
    WARNING_LOG("Freeing {}'s parameters failed: {}", name, Librashader::DescribeAndFreeError(err));
  c.preset_free(&preset);

  m_name = std::move(name);
  m_path = std::move(path);
  m_params_to_push.clear();
  return true;
}

void PostProcessing::SlangShader::OnOptionChanged(const ShaderOption& option)
{
  if (std::find(m_params_to_push.begin(), m_params_to_push.end(), option.name) == m_params_to_push.end())
    m_params_to_push.push_back(option.name);
}

void PostProcessing::SlangShader::PushParameters(bool all)
{
  if (!m_chain)
    return;

  for (const ShaderOption& option : m_options)
  {
    if (all || std::find(m_params_to_push.begin(), m_params_to_push.end(), option.name) != m_params_to_push.end())
      m_chain->SetParameter(option.name.c_str(), option.value[0].float_value);
  }
  m_params_to_push.clear();
}

void PostProcessing::SlangShader::Latch(std::string message)
{
  ERROR_LOG("{}", message);
  Host::AddIconOSDMessage("PostProcessSlang", ICON_FA_EXCLAMATION_TRIANGLE, std::move(message),
                          Host::OSD_ERROR_DURATION);
  m_chain.reset();
  m_failed = true;
}

bool PostProcessing::SlangShader::ResizeOutput(GPUTexture::Format format, u32 width, u32 height)
{
  // The stage's textures follow the picture, so they're sized in Apply().
  return true;
}

bool PostProcessing::SlangShader::CreatePipelines(GPUTexture::Format format)
{
  if (m_native_copy_pipeline && m_output_copy_pipeline && m_output_format == format)
    return true;

  const RenderAPI api = g_gpu_device->GetRenderAPI();
  ShaderGen shadergen(api, ShaderGen::GetShaderLanguageForAPI(api), false, false);
  std::unique_ptr<GPUShader> vs =
    g_gpu_device->CreateShader(GPUShaderStage::Vertex, shadergen.GetLanguage(),
                               shadergen.GenerateScreenQuadVertexShader());
  std::unique_ptr<GPUShader> fs =
    g_gpu_device->CreateShader(GPUShaderStage::Fragment, shadergen.GetLanguage(),
                               shadergen.GenerateCopyFragmentShader());
  if (!vs || !fs)
    return false;

  GPUPipeline::GraphicsConfig plconfig;
  plconfig.layout = GPUPipeline::Layout::SingleTextureAndUBO;
  plconfig.primitive = GPUPipeline::Primitive::Triangles;
  plconfig.rasterization = GPUPipeline::RasterizationState::GetNoCullState();
  plconfig.depth = GPUPipeline::DepthState::GetNoTestsState();
  plconfig.blend = GPUPipeline::BlendState::GetNoBlendingState();
  plconfig.samples = 1;
  plconfig.per_sample_shading = false;
  plconfig.render_pass_flags = GPUPipeline::NoRenderPassFlags;
  plconfig.vertex_shader = vs.get();
  plconfig.fragment_shader = fs.get();
  plconfig.geometry_shader = nullptr;

  plconfig.SetTargetFormats(GPUTexture::Format::RGBA8);
  if (!(m_native_copy_pipeline = g_gpu_device->CreatePipeline(plconfig)))
    return false;

  plconfig.SetTargetFormats(format);
  if (!(m_output_copy_pipeline = g_gpu_device->CreatePipeline(plconfig)))
    return false;

  m_output_format = format;
  return true;
}

GPUPipeline* PostProcessing::SlangShader::GetBoxPipeline(u32 factor)
{
  if (m_box_pipeline && m_box_factor == factor)
    return m_box_pipeline.get();

  m_box_pipeline.reset();
  m_box_factor = 0;

  const RenderAPI api = g_gpu_device->GetRenderAPI();
  ShaderGen shadergen(api, ShaderGen::GetShaderLanguageForAPI(api), false, false);
  std::unique_ptr<GPUShader> vs =
    g_gpu_device->CreateShader(GPUShaderStage::Vertex, shadergen.GetLanguage(),
                               shadergen.GenerateScreenQuadVertexShader());
  std::unique_ptr<GPUShader> fs =
    g_gpu_device->CreateShader(GPUShaderStage::Fragment, shadergen.GetLanguage(),
                               shadergen.GenerateBoxDownsampleFragmentShader(factor));
  if (!vs || !fs)
    return nullptr;

  GPUPipeline::GraphicsConfig plconfig;
  plconfig.layout = GPUPipeline::Layout::SingleTextureAndUBO;
  plconfig.primitive = GPUPipeline::Primitive::Triangles;
  plconfig.rasterization = GPUPipeline::RasterizationState::GetNoCullState();
  plconfig.depth = GPUPipeline::DepthState::GetNoTestsState();
  plconfig.blend = GPUPipeline::BlendState::GetNoBlendingState();
  plconfig.samples = 1;
  plconfig.per_sample_shading = false;
  plconfig.render_pass_flags = GPUPipeline::NoRenderPassFlags;
  plconfig.vertex_shader = vs.get();
  plconfig.fragment_shader = fs.get();
  plconfig.geometry_shader = nullptr;
  plconfig.SetTargetFormats(GPUTexture::Format::RGBA8);
  if (!(m_box_pipeline = g_gpu_device->CreatePipeline(plconfig)))
  {
    WARNING_LOG("No box filter for {}x, scaling the picture bilinearly instead.", factor);
    return nullptr;
  }

  m_box_factor = factor;
  return m_box_pipeline.get();
}

bool PostProcessing::SlangShader::CompilePipeline(GPUTexture::Format format, u32 width, u32 height,
                                                  ProgressCallback* progress)
{
  // A preset that failed stays off, without a new message, until the stage is replaced or the shaders reload.
  if (m_failed)
    return false;

  if (!CreatePipelines(format))
  {
    ERROR_LOG("Failed to create the slang stage's copy pipelines.");
    return false;
  }

  if (m_chain)
    return true;

  if (!g_gpu_device->SupportsSlangPresets())
  {
    Latch(fmt::format(TRANSLATE_FS("PostProcessing", "Slang presets aren't supported by the {} renderer."),
                      GPUDevice::RenderAPIToString(g_gpu_device->GetRenderAPI())));
    return false;
  }

  Error error;
  m_chain = g_gpu_device->CreateSlangChain(m_path, &error);
  if (!m_chain)
  {
    Latch(fmt::format(TRANSLATE_FS("PostProcessing", "Slang preset {} failed: {}"), m_name, error.GetDescription()));
    return false;
  }

  // A new chain starts from the preset's defaults: give it every option's value.
  PushParameters(true);
  INFO_LOG("Slang preset {} loaded.", m_name);
  return true;
}

void PostProcessing::SlangShader::DrawTexture(GPUPipeline* pipeline, GPUTexture* src, const GSVector4& src_uv,
                                              const GSVector4i& rect, bool linear, const float* texel)
{
  struct Uniforms
  {
    float src_rect[4];
    float src_texel[4];
  } uniforms = {};
  GSVector4::store<false>(uniforms.src_rect, src_uv);
  if (texel)
  {
    uniforms.src_texel[0] = texel[0];
    uniforms.src_texel[1] = texel[1];
  }

  g_gpu_device->SetPipeline(pipeline);
  g_gpu_device->SetTextureSampler(0, src, linear ? g_gpu_device->GetLinearSampler() : g_gpu_device->GetNearestSampler());
  g_gpu_device->SetViewportAndScissor(rect);
  g_gpu_device->PushUniformBuffer(&uniforms, texel ? sizeof(uniforms) : sizeof(uniforms.src_rect));
  g_gpu_device->Draw(3, 0);
}

bool PostProcessing::SlangShader::BeginOutput(GPUTexture* final_target)
{
  if (!final_target)
    return g_gpu_device->BeginPresent(false);

  g_gpu_device->SetRenderTargets(&final_target, 1, nullptr);
  g_gpu_device->ClearRenderTarget(final_target, 0);
  return true;
}

// The UV rect of a texel rect, flipped vertically when asked (src_rect's height goes negative).
static GSVector4 TexelRectToUV(const GSVector4i& rect, const GPUTexture* tex, bool flip)
{
  const float rcp_w = 1.0f / static_cast<float>(tex->GetWidth());
  const float rcp_h = 1.0f / static_cast<float>(tex->GetHeight());
  const float x = static_cast<float>(rect.left) * rcp_w;
  const float w = static_cast<float>(rect.width()) * rcp_w;
  const float y = static_cast<float>(rect.top) * rcp_h;
  const float h = static_cast<float>(rect.height()) * rcp_h;
  return flip ? GSVector4(x, y + h, w, -h) : GSVector4(x, y, w, h);
}

bool PostProcessing::SlangShader::Apply(GPUTexture* input_color, GPUTexture* input_depth, GPUTexture* final_target,
                                        GSVector4i final_rect, s32 orig_width, s32 orig_height, s32 native_width,
                                        s32 native_height, u32 target_width, u32 target_height,
                                        const PictureInfo& picture)
{
  GL_SCOPE_FMT("Slang preset {}", m_name);

  const bool from_game = (picture.game_texture != nullptr);
  GPUTexture* const src = from_game ? picture.game_texture : input_color;
  const GSVector4i out_rect = Slang::ClampToTarget(picture.rect, target_width, target_height);
  const GSVector4i src_rect =
    from_game ? picture.game_view : Slang::ClampToTarget(picture.rect, src->GetWidth(), src->GetHeight());
  const Slang::PictureSize native = {picture.native_width, picture.native_height};
  const bool src_top_first = from_game || picture.rows_top_first;

  bool shaded = false;
  if (m_chain && Slang::IsUsable(out_rect, native) && !src_rect.rempty())
  {
    const bool bottom_first = m_chain->ExpectsBottomRowFirst();
    const u32 out_w = static_cast<u32>(out_rect.width()), out_h = static_cast<u32>(out_rect.height());
    if ((!m_native || m_native->GetWidth() != native.width || m_native->GetHeight() != native.height))
    {
      g_gpu_device->RecycleTexture(std::move(m_native));
      m_native = g_gpu_device->FetchTexture(native.width, native.height, 1, 1, 1, GPUTexture::Type::RenderTarget,
                                            GPUTexture::Format::RGBA8);
    }
    if ((!m_result || m_result->GetWidth() != out_w || m_result->GetHeight() != out_h))
    {
      g_gpu_device->RecycleTexture(std::move(m_result));
      m_result = g_gpu_device->FetchTexture(out_w, out_h, 1, 1, 1, GPUTexture::Type::RenderTarget,
                                            GPUTexture::Format::RGBA8);
    }

    if (m_native && m_result)
    {
      // 1. The picture at the console's resolution, oriented as the renderer's librashader runtime wants it.
      src->MakeReadyForSampling();
      g_gpu_device->SetRenderTarget(m_native.get());
      const GSVector4 src_uv = TexelRectToUV(src_rect, src, bottom_first && src_top_first);
      const GSVector4i native_rect(0, 0, static_cast<s32>(native.width), static_cast<s32>(native.height));
      const u32 factor = Slang::BoxFactor(src_rect, native);
      GPUPipeline* box = factor ? GetBoxPipeline(factor) : nullptr;
      if (box)
      {
        const float texel[2] = {1.0f / static_cast<float>(src->GetWidth()), 1.0f / static_cast<float>(src->GetHeight())};
        DrawTexture(box, src, src_uv, native_rect, false, texel);
      }
      else
      {
        DrawTexture(m_native_copy_pipeline.get(), src, src_uv, native_rect, true);
      }
      m_native->MakeReadyForSampling();

      // 2. The preset, into a texture the size of the picture.
      PushParameters(false);
      Error error;
      if (m_chain->Frame(m_native.get(), m_result.get(), m_frame_count++, &error))
      {
        m_result->MakeReadyForSampling();
        shaded = true;
      }
      else
      {
        Latch(fmt::format(TRANSLATE_FS("PostProcessing", "Slang preset {} failed: {}"), m_name,
                          error.GetDescription()));
      }
    }
  }

  // 3. The output: the input as it is (a stage before may have drawn outside the picture), then the picture.
  if (!BeginOutput(final_target))
    return false;

  if (!from_game)
  {
    DrawTexture(m_output_copy_pipeline.get(), input_color, GSVector4(0.0f, 0.0f, 1.0f, 1.0f),
                GSVector4i(0, 0, static_cast<s32>(target_width), static_cast<s32>(target_height)), false);
  }

  if (out_rect.rempty())
    return true;

  if (shaded)
  {
    const bool flip = m_chain && m_chain->ExpectsBottomRowFirst() && picture.rows_top_first;
    DrawTexture(m_output_copy_pipeline.get(), m_result.get(),
                TexelRectToUV(GSVector4i(0, 0, out_rect.width(), out_rect.height()), m_result.get(), flip), out_rect,
                false);
  }
  else if (from_game && !src_rect.rempty())
  {
    // Unshaded (no chain, or this frame failed): the game's picture where it goes, so the game keeps showing.
    DrawTexture(m_output_copy_pipeline.get(), src, TexelRectToUV(src_rect, src, !picture.rows_top_first), out_rect,
                true);
  }

  return true;
}
