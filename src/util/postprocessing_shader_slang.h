// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "postprocessing_shader.h"

#include <memory>
#include <string>
#include <string_view>
#include <vector>

class GPUPipeline;
class GPUTexture;
class SlangChain;

namespace PostProcessing::Slang {

/// Whether a stage's name is a slang preset's: it ends with ".slangp" (any case).
bool IsPresetName(std::string_view name);

/// The preset's absolute path under the shaders folder, or empty when the name is empty, absolute, or has a ".."
/// component, so a stage name never reaches outside the folder. '\' is taken as '/'.
std::string ResolvePresetPath(std::string_view shaders_root, std::string_view name);

/// Every preset under the folder, relative with '/' separators, sorted. Skips anything under "__MACOSX" and files or
/// folders whose names start with '.'. Reads directory entries only, never a preset.
std::vector<std::string> FindPresets(std::string_view shaders_root);

} // namespace PostProcessing::Slang

namespace PostProcessing {

/// A RetroArch slang preset (.slangp) as a stage, run by librashader through the renderer's SlangChain. It feeds the
/// preset the game's picture at the console's resolution and draws the result where the picture sits.
class SlangShader final : public Shader
{
public:
  SlangShader();
  ~SlangShader() override;

  bool IsValid() const override;
  bool WantsDepthBuffer() const override;
  bool ReadsGamePicture() const override;

  /// Parses the preset (no GPU) for its #pragma parameters, which become float options in preset order.
  bool LoadFromFile(std::string name, std::string path, bool only_config, Error* error);

  bool ResizeOutput(GPUTexture::Format format, u32 width, u32 height) override;
  bool CompilePipeline(GPUTexture::Format format, u32 width, u32 height, ProgressCallback* progress) override;
  bool Apply(GPUTexture* input_color, GPUTexture* input_depth, GPUTexture* final_target, GSVector4i final_rect,
             s32 orig_width, s32 orig_height, s32 native_width, s32 native_height, u32 target_width,
             u32 target_height, const PictureInfo& picture) override;

  /// The parameters whose values changed since the running chain last had them.
  const std::vector<std::string>& GetParametersToPush() const { return m_params_to_push; }

protected:
  void OnOptionChanged(const ShaderOption& option) override;

private:
  bool CreatePipelines(GPUTexture::Format format);
  GPUPipeline* GetBoxPipeline(u32 factor);
  void PushParameters(bool all);
  void Latch(std::string message);

  // Draws src_uv of src over the bound target's rect, with the given pipeline and sampler.
  void DrawTexture(GPUPipeline* pipeline, GPUTexture* src, const GSVector4& src_uv, const GSVector4i& rect,
                   bool linear, const float* texel = nullptr);
  bool BeginOutput(GPUTexture* final_target);

  std::string m_path;
  std::unique_ptr<SlangChain> m_chain;
  bool m_failed = false;
  u64 m_frame_count = 0;
  std::vector<std::string> m_params_to_push;

  GPUTexture::Format m_output_format = GPUTexture::Format::Unknown;
  std::unique_ptr<GPUPipeline> m_native_copy_pipeline;
  std::unique_ptr<GPUPipeline> m_output_copy_pipeline;
  std::unique_ptr<GPUPipeline> m_box_pipeline;
  u32 m_box_factor = 0;
  std::unique_ptr<GPUTexture> m_native;
  std::unique_ptr<GPUTexture> m_result;
};

} // namespace PostProcessing
