// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/gpu_hw_shadergen.h"

#include "glad/gl.h"

#include <gtest/gtest.h>

#include <string>

// The shaders the hardware renderer generates for OpenGL ES, against a stand-in for the AYN Thor's context (Adreno 740,
// OpenGL ES 3.2 with EXT_blend_func_extended and EXT_shader_framebuffer_fetch): ShaderGen reads only glad's flags and
// the context's strings.

namespace {

constexpr const char* BLEND_FUNC_EXTENDED = "#extension GL_EXT_blend_func_extended : require";

const GLubyte* GLES32String(GLenum name)
{
  switch (name)
  {
    case GL_VENDOR:
      return reinterpret_cast<const GLubyte*>("Qualcomm");
    case GL_RENDERER:
      return reinterpret_cast<const GLubyte*>("Adreno (TM) 740");
    case GL_SHADING_LANGUAGE_VERSION:
      return reinterpret_cast<const GLubyte*>("OpenGL ES GLSL ES 3.20");
    default:
      return reinterpret_cast<const GLubyte*>("");
  }
}

class GLESShaderGen : public ::testing::Test
{
protected:
  void SetUp() override
  {
    m_saved = {glad_glGetString,       GLAD_GL_ES_VERSION_3_0,          GLAD_GL_ES_VERSION_3_1,
               GLAD_GL_ES_VERSION_3_2, GLAD_GL_EXT_blend_func_extended, GLAD_GL_EXT_shader_framebuffer_fetch};
    glad_glGetString = GLES32String;
    GLAD_GL_ES_VERSION_3_0 = GLAD_GL_ES_VERSION_3_1 = GLAD_GL_ES_VERSION_3_2 = 1;
    GLAD_GL_EXT_blend_func_extended = 1;
    GLAD_GL_EXT_shader_framebuffer_fetch = 1;
  }

  void TearDown() override
  {
    glad_glGetString = m_saved.get_string;
    GLAD_GL_ES_VERSION_3_0 = m_saved.es_3_0;
    GLAD_GL_ES_VERSION_3_1 = m_saved.es_3_1;
    GLAD_GL_ES_VERSION_3_2 = m_saved.es_3_2;
    GLAD_GL_EXT_blend_func_extended = m_saved.blend_func_extended;
    GLAD_GL_EXT_shader_framebuffer_fetch = m_saved.framebuffer_fetch;
  }

  // Dual-source blending and framebuffer fetch, as on the Thor; everything else at its default.
  static GPU_HW_ShaderGen ShaderGen()
  {
    return GPU_HW_ShaderGen(RenderAPI::OpenGLES, 1, 1, false, false, false, false, false, true, true, false);
  }

private:
  struct Saved
  {
    PFNGLGETSTRINGPROC get_string;
    int es_3_0, es_3_1, es_3_2, blend_func_extended, framebuffer_fetch;
  } m_saved = {};
};

bool Contains(const std::string& source, const char* text)
{
  return source.find(text) != std::string::npos;
}

} // namespace

// Adreno refuses to link a second render target in a shader that enables EXT_blend_func_extended ("Output o_col1
// location or component exceeds max allowed"), so the PGXP depth buffer's extract pass, which writes depth to one,
// leaves the extension off.
TEST_F(GLESShaderGen, DepthExtractHasASecondTargetWithoutBlendFuncExtended)
{
  const std::string source = ShaderGen().GenerateVRAMExtractFragmentShader(false, true);
  EXPECT_TRUE(Contains(source, "layout(location = 1) out float4 o_col1;")) << source;
  EXPECT_FALSE(Contains(source, BLEND_FUNC_EXTENDED)) << source;
}

// Shaders with one output don't need it either.
TEST_F(GLESShaderGen, SingleOutputShadersLeaveBlendFuncExtendedOff)
{
  GPU_HW_ShaderGen shadergen = ShaderGen();
  EXPECT_FALSE(Contains(shadergen.GenerateVRAMExtractFragmentShader(false, false), BLEND_FUNC_EXTENDED));
  EXPECT_FALSE(Contains(
    shadergen.GenerateBatchFragmentShader(GPU_HW::BatchRenderMode::TransparencyDisabled, GPUTransparencyMode::Disabled,
                                          GPU_HW::BatchTextureMode::Direct16Bit, false, GPUTextureFilter::Nearest,
                                          false, false, false, false, false, false, false, false),
    BLEND_FUNC_EXTENDED));
}

// The batches that blend with a second source keep it: their second output is index 1 of location 0.
TEST_F(GLESShaderGen, DualSourceBatchesEnableBlendFuncExtended)
{
  const std::string source = ShaderGen().GenerateBatchFragmentShader(
    GPU_HW::BatchRenderMode::TransparentAndOpaque, GPUTransparencyMode::Disabled, GPU_HW::BatchTextureMode::Direct16Bit,
    false, GPUTextureFilter::Nearest, false, false, false, false, false, false, false, false);
  EXPECT_TRUE(Contains(source, "layout(location = 0, index = 1) out float4 o_col1;")) << source;
  EXPECT_TRUE(Contains(source, BLEND_FUNC_EXTENDED)) << source;
}
