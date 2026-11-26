// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Post-processing stages across a resize. The rule is tested on its own; the stages run on a real GPU device
// (opt-in: see gpu_test_device.h).

#include "gpu_test_device.h"

#include "util/gpu_device.h"
#include "util/postprocessing.h"
#include "util/postprocessing_shader_fx.h"
#include "util/postprocessing_shader_glsl.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/path.h"

#include <gtest/gtest.h>

#include <cstdlib>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>

namespace {

using PostProcessing::StageNeedsCompile;
constexpr GPUTexture::Format RGBA8 = GPUTexture::Format::RGBA8;
constexpr GPUTexture::Format RGBA16F = GPUTexture::Format::RGBA16F;
constexpr GPUTexture::Format UNKNOWN = GPUTexture::Format::Unknown;

TEST(PostProcessingRule, NothingChangedBuildsNothing)
{
  EXPECT_FALSE(StageNeedsCompile(false, RGBA8, 640, 480, RGBA8, 640, 480));
  EXPECT_FALSE(StageNeedsCompile(true, RGBA8, 640, 480, RGBA8, 640, 480));
}

TEST(PostProcessingRule, ASizeChangeRebuildsOnlyWhatDependsOnSize)
{
  EXPECT_FALSE(StageNeedsCompile(false, RGBA8, 640, 480, RGBA8, 1280, 960));
  EXPECT_TRUE(StageNeedsCompile(true, RGBA8, 640, 480, RGBA8, 1280, 960));
  EXPECT_TRUE(StageNeedsCompile(true, RGBA8, 640, 480, RGBA8, 640, 481));
}

TEST(PostProcessingRule, AFormatChangeOrAFirstBuildRebuildsEverything)
{
  EXPECT_TRUE(StageNeedsCompile(false, RGBA8, 640, 480, RGBA16F, 640, 480));
  EXPECT_TRUE(StageNeedsCompile(false, UNKNOWN, 0, 0, RGBA8, 640, 480));
  EXPECT_TRUE(StageNeedsCompile(true, UNKNOWN, 0, 0, RGBA8, 640, 480));
}

constexpr u32 SMALL_W = 64, SMALL_H = 48;
constexpr u32 LARGE_W = 96, LARGE_H = 72;

// Two passes; the first scales by BUFFER_WIDTH, so its output depends on the size it was built for.
constexpr const char* FX_SHADER = R"(
texture BackBufferTex : COLOR;
sampler BackBuffer { Texture = BackBufferTex; };
texture HalfTex { Width = BUFFER_WIDTH; Height = BUFFER_HEIGHT; Format = RGBA8; };
sampler Half { Texture = HalfTex; };

void FullscreenVS(in uint id : SV_VertexID, out float4 position : SV_Position, out float2 texcoord : TEXCOORD)
{
  texcoord.x = (id == 2) ? 2.0 : 0.0;
  texcoord.y = (id == 1) ? 2.0 : 0.0;
  position = float4(texcoord * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
}

float4 ScalePS(float4 position : SV_Position, float2 texcoord : TEXCOORD) : SV_Target
{
  return tex2D(BackBuffer, texcoord) * (BUFFER_WIDTH / 128.0);
}

float4 CopyPS(float4 position : SV_Position, float2 texcoord : TEXCOORD) : SV_Target
{
  return tex2D(Half, texcoord);
}

technique ResizeTest
{
  pass { VertexShader = FullscreenVS; PixelShader = ScalePS; RenderTarget = HalfTex; }
  pass { VertexShader = FullscreenVS; PixelShader = CopyPS; }
}
)";

class PostProcessingGPU : public ::testing::Test
{
protected:
  void SetUp() override
  {
    if (!GPUTestDevice::API().has_value())
      GTEST_SKIP() << "GOOSESTATION_GPU_TEST_API isn't set";
    if (!FileSystem::DirectoryExists(GOOSESTATION_TEST_RESOURCES_DIR))
      GTEST_SKIP() << "No source tree at " << GOOSESTATION_TEST_RESOURCES_DIR;

    Error error;
    ASSERT_TRUE(GPUTestDevice::Create({}, &error)) << error.GetDescription();

    std::error_code ec;
    m_dir = (std::filesystem::temp_directory_path(ec) / ("common-tests-pp-gpu-" + std::to_string(std::rand())))
              .generic_string();
    ASSERT_TRUE(FileSystem::CreateDirectory(m_dir.c_str(), true));
    ASSERT_TRUE(FileSystem::WriteStringToFile(Path::Combine(m_dir, "resize.fx").c_str(), FX_SHADER));

    // A small gradient, so every pixel tells.
    m_pixels.resize(LARGE_W * LARGE_H);
    for (u32 y = 0; y < LARGE_H; y++)
      for (u32 x = 0; x < LARGE_W; x++)
        m_pixels[y * LARGE_W + x] = 0xFF000000u | ((x * 2) & 0xFF) | (((y * 3) & 0xFF) << 8) | (0x40u << 16);
  }

  void TearDown() override
  {
    m_textures.clear();
    if (g_gpu_device)
      PostProcessing::Shutdown(); // the shared samplers go with the device
    GPUTestDevice::Destroy();
    if (!m_dir.empty())
      FileSystem::RecursiveDeleteDirectory(m_dir.c_str());
  }

  std::unique_ptr<PostProcessing::Shader> LoadGLSL()
  {
    auto shader = std::make_unique<PostProcessing::GLSLShader>();
    Error error;
    EXPECT_TRUE(shader->LoadFromFile(
      "simple-brightness", Path::Combine(GOOSESTATION_TEST_RESOURCES_DIR, "shaders/simple-brightness.glsl").c_str(),
      &error))
      << error.GetDescription();
    return shader;
  }

  std::unique_ptr<PostProcessing::Shader> LoadFX()
  {
    auto shader = std::make_unique<PostProcessing::ReShadeFXShader>();
    Error error;
    EXPECT_TRUE(shader->LoadFromFile("resize", Path::Combine(m_dir, "resize.fx"), false, &error))
      << error.GetDescription();
    return shader;
  }

  static bool Build(PostProcessing::Shader* shader, u32 width, u32 height)
  {
    return shader->CompilePipeline(RGBA8, width, height, nullptr) && shader->ResizeOutput(RGBA8, width, height);
  }

  // Runs the stage over the gradient at the large size.
  std::vector<u32> Run(PostProcessing::Shader* shader)
  {
    m_textures.push_back(g_gpu_device->CreateTexture(LARGE_W, LARGE_H, 1, 1, 1, GPUTexture::Type::Texture, RGBA8,
                                                     m_pixels.data(), LARGE_W * sizeof(u32)));
    GPUTexture* input = m_textures.back().get();
    m_textures.push_back(g_gpu_device->CreateTexture(LARGE_W, LARGE_H, 1, 1, 1, GPUTexture::Type::RenderTarget, RGBA8));
    GPUTexture* output = m_textures.back().get();

    PostProcessing::PictureInfo picture;
    picture.rect = GSVector4i(0, 0, LARGE_W, LARGE_H);
    picture.native_width = LARGE_W;
    picture.native_height = LARGE_H;
    picture.rows_top_first = true;
    EXPECT_TRUE(shader->Apply(input, nullptr, output, GSVector4i(0, 0, LARGE_W, LARGE_H), LARGE_W, LARGE_H, LARGE_W,
                              LARGE_H, LARGE_W, LARGE_H, picture));

    std::vector<u32> result(LARGE_W * LARGE_H);
    std::unique_ptr<GPUDownloadTexture> dl = g_gpu_device->CreateDownloadTexture(LARGE_W, LARGE_H, RGBA8);
    EXPECT_TRUE(dl);
    if (!dl)
      return result;
    output->MakeReadyForSampling();
    dl->CopyFromTexture(0, 0, output, 0, 0, LARGE_W, LARGE_H, 0, 0, true);
    dl->Flush();
    EXPECT_TRUE(dl->ReadTexels(0, 0, LARGE_W, LARGE_H, result.data(), LARGE_W * sizeof(u32)));
    return result;
  }

  std::string m_dir;
  std::vector<u32> m_pixels;
  std::vector<std::unique_ptr<GPUTexture>> m_textures;
};

TEST_F(PostProcessingGPU, AResizeRebuildsOnlyFX)
{
  std::unique_ptr<PostProcessing::Shader> glsl = LoadGLSL();
  std::unique_ptr<PostProcessing::Shader> fx = LoadFX();
  ASSERT_TRUE(glsl && fx);
  EXPECT_FALSE(glsl->CompileDependsOnSize());
  EXPECT_TRUE(fx->CompileDependsOnSize());

  // GLSL built for the small size and only resized draws what one built for the large size does.
  ASSERT_TRUE(Build(glsl.get(), SMALL_W, SMALL_H));
  ASSERT_TRUE(glsl->ResizeOutput(RGBA8, LARGE_W, LARGE_H));
  const std::vector<u32> glsl_resized = Run(glsl.get());
  std::unique_ptr<PostProcessing::Shader> glsl_fresh = LoadGLSL();
  ASSERT_TRUE(Build(glsl_fresh.get(), LARGE_W, LARGE_H));
  EXPECT_EQ(glsl_resized, Run(glsl_fresh.get()));

#ifdef __APPLE__
  // ReShade FX's GLSL uses binding layouts, which Apple's OpenGL 4.1 lacks (not new here).
  if (g_gpu_device->GetRenderAPI() == RenderAPI::OpenGL)
    GTEST_SKIP() << "ReShade FX doesn't build on Apple's OpenGL";
#endif

  // FX rebuilt at the large size (through the pipeline batch) draws what one first built there does, and not what
  // the small size's build would.
  ASSERT_TRUE(Build(fx.get(), SMALL_W, SMALL_H));
  const std::vector<u32> fx_small = Run(fx.get());
  ASSERT_TRUE(Build(fx.get(), LARGE_W, LARGE_H));
  const std::vector<u32> fx_rebuilt = Run(fx.get());
  std::unique_ptr<PostProcessing::Shader> fx_fresh = LoadFX();
  ASSERT_TRUE(Build(fx_fresh.get(), LARGE_W, LARGE_H));
  EXPECT_EQ(fx_rebuilt, Run(fx_fresh.get()));
  EXPECT_NE(fx_rebuilt, fx_small);
}

} // namespace
