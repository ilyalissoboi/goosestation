// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Building shaders and pipelines on several threads, on a real GPU device (opt-in: see gpu_test_device.h).

#include "gpu_test_device.h"

#include "core/gpu_hw.h"
#include "core/gpu_hw_shadergen.h"

#include "util/compress_helpers.h"
#include "util/gpu_device.h"
#include "util/gpu_pipeline_batch.h"
#include "util/gpu_texture.h"

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

std::string TempDir(const char* name)
{
  std::error_code ec;
  const std::string dir =
    (std::filesystem::temp_directory_path(ec) / (std::string(name) + "-" + std::to_string(std::rand()))).generic_string();
  FileSystem::CreateDirectory(dir.c_str(), true);
  return dir;
}

// The hardware renderer's batch fragment shaders at the defaults (1x, no MSAA): a realistic, varied set.
std::vector<std::string> BatchFragmentShaders(RenderAPI api)
{
  GPU_HW_ShaderGen shadergen(api, 1, 1, false, false, false, false, false, true, false, false);
  std::vector<std::string> sources;
  for (u8 render_mode = 0; render_mode < 4; render_mode++)
  {
    for (u8 texture_mode = 0; texture_mode < 4; texture_mode++)
    {
      for (u8 dithering = 0; dithering < 2; dithering++)
      {
        for (u8 interlacing = 0; interlacing < 2; interlacing++)
        {
          sources.push_back(shadergen.GenerateBatchFragmentShader(
            static_cast<GPU_HW::BatchRenderMode>(render_mode), GPUTransparencyMode::Disabled,
            static_cast<GPU_HW::BatchTextureMode>(texture_mode), false, GPUTextureFilter::Nearest, false, false,
            dithering != 0, interlacing != 0, false, false, false, false));
        }
      }
    }
  }
  return sources;
}

class GPUBatchTest : public ::testing::Test
{
protected:
  void SetUp() override
  {
    if (!GPUTestDevice::API().has_value())
      GTEST_SKIP() << "GOOSESTATION_GPU_TEST_API isn't set";
  }

  void TearDown() override
  {
    GPUTestDevice::Destroy();
    for (const std::string& dir : m_dirs)
      FileSystem::RecursiveDeleteDirectory(dir.c_str());
  }

  std::string NewDir(const char* name) { return m_dirs.emplace_back(TempDir(name)); }

  // The shader cache's files in dir, as bytes.
  static std::string CacheFiles(const std::string& dir)
  {
    std::string all;
    FileSystem::FindResultsArray files;
    FileSystem::FindFiles(dir.c_str(), "*_shaders.*", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_SORT_BY_NAME, &files);
    for (const FILESYSTEM_FIND_DATA& f : files)
      all += FileSystem::ReadFileToString(f.FileName.c_str()).value_or("");
    return all;
  }

  std::vector<std::string> m_dirs;
};

using ShaderBatchGPU = GPUBatchTest;

TEST_F(ShaderBatchGPU, ParallelShadersMatchSerial)
{
  std::string files[2];
  const u32 threads[2] = {1, 8};
  for (u32 run = 0; run < 2; run++)
  {
    const std::string dir = NewDir("common-tests-shader-batch-gpu");
    Error error;
    ASSERT_TRUE(GPUTestDevice::Create(dir, &error)) << error.GetDescription();

    const std::vector<std::string> sources = BatchFragmentShaders(g_gpu_device->GetRenderAPI());
    const GPUShaderLanguage language = GPU_HW_ShaderGen(g_gpu_device->GetRenderAPI(), 1, 1, false, false, false,
                                                        false, false, true, false, false)
                                         .GetLanguage();
    std::vector<GPUDevice::ShaderRequest> requests;
    for (const std::string& s : sources)
      requests.push_back({GPUShaderStage::Fragment, language, s, "main"});
    std::vector<std::unique_ptr<GPUShader>> shaders(requests.size());
    ASSERT_TRUE(g_gpu_device->CreateShaders(requests, shaders, threads[run], {}, &error)) << error.GetDescription();
    for (const std::unique_ptr<GPUShader>& s : shaders)
      ASSERT_TRUE(s);

    shaders.clear();
    GPUTestDevice::Destroy();
    files[run] = CacheFiles(dir);
  }

  if (GPUTestDevice::API() != RenderAPI::OpenGL && GPUTestDevice::API() != RenderAPI::OpenGLES)
    EXPECT_FALSE(files[0].empty());
  EXPECT_EQ(files[0], files[1]);
}

using PipelineBatchGPU = GPUBatchTest;

constexpr u32 TARGET_SIZE = 16;

// 64 variants of a fill pipeline: four blend states, each with all sixteen colour write masks.
std::vector<GPUPipeline::GraphicsConfig> FillConfigs()
{
  std::vector<GPUPipeline::GraphicsConfig> configs;
  for (u32 blend = 0; blend < 4; blend++)
  {
    for (u8 mask = 0; mask < 16; mask++)
    {
      GPUPipeline::GraphicsConfig c = {};
      c.layout = GPUPipeline::Layout::SingleTextureAndPushConstants;
      c.primitive = GPUPipeline::Primitive::Triangles;
      c.rasterization = GPUPipeline::RasterizationState::GetNoCullState();
      c.depth = GPUPipeline::DepthState::GetNoTestsState();
      c.blend = (blend == 1) ? GPUPipeline::BlendState::GetAlphaBlendingState() :
                               GPUPipeline::BlendState::GetNoBlendingState();
      if (blend >= 2)
      {
        c.blend.enable = true;
        c.blend.src_blend = GPUPipeline::BlendFunc::One;
        c.blend.dst_blend = (blend == 2) ? GPUPipeline::BlendFunc::One : GPUPipeline::BlendFunc::ConstantColor;
        c.blend.blend_op = (blend == 2) ? GPUPipeline::BlendOp::Add : GPUPipeline::BlendOp::ReverseSubtract;
        c.blend.src_alpha_blend = GPUPipeline::BlendFunc::One;
        c.blend.dst_alpha_blend = GPUPipeline::BlendFunc::Zero;
        c.blend.alpha_blend_op = GPUPipeline::BlendOp::Add;
        c.blend.constant = 0x00808080u;
      }
      c.blend.write_mask = mask;
      c.SetTargetFormats(GPUTexture::Format::RGBA8);
      c.samples = 1;
      c.render_pass_flags = GPUPipeline::NoRenderPassFlags;
      configs.push_back(c);
    }
  }
  return configs;
}

struct FillUniforms
{
  u32 dst_coords[2];
  u32 end_coords[2];
  float fill_color[4];
  u32 interlaced_displayed_field;
  u32 pad[3];
};

// Builds the fill pipelines on threads threads, draws each over a cleared target, and returns every pixel drawn.
std::vector<u32> DrawFills(u32 threads)
{
  const RenderAPI api = g_gpu_device->GetRenderAPI();
  GPU_HW_ShaderGen shadergen(api, 1, 1, false, false, false, false, false, true, false, false);

  GPUPipelineBatch batch;
  const GPUPipelineBatch::Handle vs = batch.AddShader(GPUShaderStage::Vertex, shadergen.GetLanguage(),
                                                      shadergen.GenerateScreenQuadVertexShader(1.0f));
  const GPUPipelineBatch::Handle fs = batch.AddShader(GPUShaderStage::Fragment, shadergen.GetLanguage(),
                                                      shadergen.GenerateVRAMFillFragmentShader(false, false));
  const std::vector<GPUPipeline::GraphicsConfig> configs = FillConfigs();
  std::vector<GPUPipeline*> slots(configs.size());
  for (size_t i = 0; i < configs.size(); i++)
    batch.AddPipeline(configs[i], vs, fs, GPUPipelineBatch::NONE, &slots[i]);
  EXPECT_EQ(batch.GetUniquePipelineCount(), configs.size());

  Error error;
  EXPECT_TRUE(batch.Build(*g_gpu_device, threads, {}, &error)) << error.GetDescription();
  const std::vector<std::unique_ptr<GPUPipeline>> pipelines = batch.TakePipelines();

  std::unique_ptr<GPUTexture> target = g_gpu_device->CreateTexture(
    TARGET_SIZE, TARGET_SIZE, 1, 1, 1, GPUTexture::Type::RenderTarget, GPUTexture::Format::RGBA8);
  std::unique_ptr<GPUDownloadTexture> dl =
    g_gpu_device->CreateDownloadTexture(TARGET_SIZE, TARGET_SIZE, GPUTexture::Format::RGBA8);
  EXPECT_TRUE(target && dl);
  if (!target || !dl)
    return {};

  std::vector<u32> pixels;
  std::vector<u32> frame(TARGET_SIZE * TARGET_SIZE);
  for (GPUPipeline* pipeline : slots)
  {
    EXPECT_TRUE(pipeline);
    if (!pipeline)
      continue;
    g_gpu_device->ClearRenderTarget(target.get(), 0xC0402010u);
    g_gpu_device->SetRenderTarget(target.get());
    g_gpu_device->SetViewportAndScissor(0, 0, TARGET_SIZE, TARGET_SIZE);
    g_gpu_device->SetPipeline(pipeline);
    const FillUniforms uniforms = {{0, 0}, {TARGET_SIZE, TARGET_SIZE}, {0.75f, 0.5f, 0.25f, 0.5f}, 0, {}};
    g_gpu_device->PushUniformBuffer(&uniforms, sizeof(uniforms));
    g_gpu_device->Draw(3, 0);
    target->MakeReadyForSampling();
    dl->CopyFromTexture(0, 0, target.get(), 0, 0, TARGET_SIZE, TARGET_SIZE, 0, 0, true);
    dl->Flush();
    EXPECT_TRUE(dl->ReadTexels(0, 0, TARGET_SIZE, TARGET_SIZE, frame.data(), TARGET_SIZE * sizeof(u32)));
    pixels.insert(pixels.end(), frame.begin(), frame.end());
  }
  return pixels;
}

// Builds the 64 fill pipelines on the current device, on eight threads.
void BuildFills()
{
  const RenderAPI api = g_gpu_device->GetRenderAPI();
  GPU_HW_ShaderGen shadergen(api, 1, 1, false, false, false, false, false, true, false, false);
  GPUPipelineBatch batch;
  const GPUPipelineBatch::Handle vs = batch.AddShader(GPUShaderStage::Vertex, shadergen.GetLanguage(),
                                                      shadergen.GenerateScreenQuadVertexShader(1.0f));
  const GPUPipelineBatch::Handle fs = batch.AddShader(GPUShaderStage::Fragment, shadergen.GetLanguage(),
                                                      shadergen.GenerateVRAMFillFragmentShader(false, false));
  const std::vector<GPUPipeline::GraphicsConfig> configs = FillConfigs();
  std::vector<GPUPipeline*> slots(configs.size());
  for (size_t i = 0; i < configs.size(); i++)
    batch.AddPipeline(configs[i], vs, fs, GPUPipelineBatch::NONE, &slots[i]);
  Error error;
  EXPECT_TRUE(batch.Build(*g_gpu_device, 8, {}, &error)) << error.GetDescription();
}

// Every file in the cache folder, with its bytes.
std::vector<std::pair<std::string, std::string>> Files(const std::string& dir)
{
  std::vector<std::pair<std::string, std::string>> files;
  FileSystem::FindResultsArray found;
  FileSystem::FindFiles(dir.c_str(), "*", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_SORT_BY_NAME, &found);
  for (const FILESYSTEM_FIND_DATA& f : found)
    files.emplace_back(Path::GetFileName(f.FileName), FileSystem::ReadFileToString(f.FileName.c_str()).value_or(""));
  return files;
}

TEST_F(PipelineBatchGPU, ACacheIsOnDiskBeforeShutdown)
{
  const std::string dir = NewDir("common-tests-pipeline-cache");
  Error error;
  ASSERT_TRUE(GPUTestDevice::Create(dir, &error)) << error.GetDescription();
  if (!g_gpu_device->GetFeatures().pipeline_cache)
    GTEST_SKIP() << "No driver pipeline cache on this renderer and driver";
  BuildFills();
  g_gpu_device->SavePipelineCacheIfGrown();

  // The run is killed here: what's on disk now is all the next run gets. (A device can't be leaked to show it: the
  // renderer's library is shared, so the shutdown runs, and the folder is put back as it was.)
  const std::vector<std::pair<std::string, std::string>> saved = Files(dir);
  bool found = false;
  for (const auto& [name, bytes] : saved)
    found |= (name.find("_pipelines.bin") != std::string::npos && !bytes.empty());
  EXPECT_TRUE(found) << "no pipeline cache written after the build";
  GPUTestDevice::Destroy();
  FileSystem::RecursiveDeleteDirectory(dir.c_str());
  FileSystem::CreateDirectory(dir.c_str(), false);
  for (const auto& [name, bytes] : saved)
    FileSystem::WriteStringToFile(Path::Combine(dir, name).c_str(), bytes);

  // The next run finds everything there: building the same pipelines adds nothing, so nothing is written.
  ASSERT_TRUE(GPUTestDevice::Create(dir, &error)) << error.GetDescription();
  BuildFills();
  g_gpu_device->SavePipelineCacheIfGrown();
  EXPECT_EQ(Files(dir), saved);
}

TEST_F(PipelineBatchGPU, ACacheTheDriverRejectsIsWrittenAgain)
{
  if (GPUTestDevice::API() != RenderAPI::Vulkan)
    GTEST_SKIP() << "Vulkan only: the test corrupts its cache's header";

  const std::string dir = NewDir("common-tests-pipeline-rejected");
  const std::string path = Path::Combine(dir, "vulkan_pipelines.bin");
  Error error;
  ASSERT_TRUE(GPUTestDevice::Create(dir, &error)) << error.GetDescription();
  BuildFills();
  GPUTestDevice::Destroy();

  // A driver update: the cache's UUID (bytes 16-31 of the header) no longer matches, at the same size.
  CompressHelpers::OptionalByteBuffer data =
    CompressHelpers::DecompressFile(CompressHelpers::CompressType::Zstandard, path.c_str(), std::nullopt, &error);
  ASSERT_TRUE(data.has_value() && data->size() > 32) << error.GetDescription();
  const u8 good = (*data)[16];
  (*data)[16] = static_cast<u8>(good ^ 0xFF);
  ASSERT_TRUE(CompressHelpers::CompressToFile(CompressHelpers::CompressType::Zstandard, path.c_str(), data->cspan(),
                                              -1, true, &error));

  // The same pipelines are built again: the cache must be written again, valid, or every start rebuilds them.
  ASSERT_TRUE(GPUTestDevice::Create(dir, &error)) << error.GetDescription();
  BuildFills();
  g_gpu_device->SavePipelineCacheIfGrown();
  GPUTestDevice::Destroy();
  data = CompressHelpers::DecompressFile(CompressHelpers::CompressType::Zstandard, path.c_str(), std::nullopt, &error);
  ASSERT_TRUE(data.has_value() && data->size() > 32);
  EXPECT_EQ((*data)[16], good);
}

TEST_F(PipelineBatchGPU, AWarmRunRewritesNothing)
{
  const std::string dir = NewDir("common-tests-pipeline-warm");
  Error error;
  ASSERT_TRUE(GPUTestDevice::Create(dir, &error)) << error.GetDescription();
  BuildFills();
  GPUTestDevice::Destroy();
  const std::vector<std::pair<std::string, std::string>> cold = Files(dir);
  if (cold.empty())
    GTEST_SKIP() << "Nothing is cached on this renderer and driver";

  ASSERT_TRUE(GPUTestDevice::Create(dir, &error)) << error.GetDescription();
  BuildFills();
  GPUTestDevice::Destroy();
  EXPECT_EQ(Files(dir), cold);
}

TEST_F(PipelineBatchGPU, AnUnfinishedBuildHoldsAutomaticAtOneThread)
{
  const std::string dir = NewDir("common-tests-guard-gpu");
  Error error;
  ASSERT_TRUE(GPUTestDevice::Create(dir, &error)) << error.GetDescription();
  if (!g_gpu_device->SupportsParallelCompile())
    GTEST_SKIP() << "This renderer builds on one thread anyway";
  const u32 threads = g_gpu_device->GetCompileThreads(ShaderCompileThreads::AUTOMATIC);
  ASSERT_GT(threads, 1u);

  // The run dies during a build with several threads: the marker is left behind.
  g_gpu_device->BeginCompilePass(threads);
  GPUTestDevice::Destroy();

  // The same renderer and driver next time: Automatic gives one thread.
  ASSERT_TRUE(GPUTestDevice::Create(dir, &error)) << error.GetDescription();
  EXPECT_EQ(g_gpu_device->GetCompileThreads(ShaderCompileThreads::AUTOMATIC), 1u);
  EXPECT_TRUE(ShaderCompileThreads::Guard::ReadNote(dir).has_value());
}

TEST_F(PipelineBatchGPU, TheDriversIdentityIsTheSameEveryRun)
{
  // The guard keys its records by this: an object's address in it (Metal's description has one) would change every
  // run, and a block would never hold.
  Error error;
  ASSERT_TRUE(GPUTestDevice::Create({}, &error)) << error.GetDescription();
  const std::string identity = g_gpu_device->GetDriverIdentity();
  EXPECT_FALSE(identity.empty());
  EXPECT_EQ(identity.find("0x"), std::string::npos) << identity;
}

TEST_F(PipelineBatchGPU, OpenGLCreatesShadersOnTheCallingThread)
{
  // Its shader creator may ask the context for the GLSL version, which a worker doesn't have.
  const std::optional<RenderAPI> api = GPUTestDevice::API();
  if (api != RenderAPI::OpenGL && api != RenderAPI::OpenGLES)
    GTEST_SKIP() << "OpenGL only";
  Error error;
  ASSERT_TRUE(GPUTestDevice::Create({}, &error)) << error.GetDescription();
  EXPECT_FALSE(g_gpu_device->SupportsParallelShaderCreation());
}

TEST_F(PipelineBatchGPU, OpenGLBatchMatchesSerial)
{
  const std::optional<RenderAPI> api = GPUTestDevice::API();
  if (api != RenderAPI::OpenGL && api != RenderAPI::OpenGLES)
    GTEST_SKIP() << "OpenGL only";

  // The same pixels, and the same program-binary file, whether the driver compiles in parallel or one at a time.
  std::vector<u32> pixels[2];
  std::vector<std::pair<std::string, std::string>> files[2];
  const u32 threads[2] = {1, 8};
  for (u32 run = 0; run < 2; run++)
  {
    const std::string dir = NewDir("common-tests-gl-batch");
    Error error;
    ASSERT_TRUE(GPUTestDevice::Create(dir, &error)) << error.GetDescription();
    pixels[run] = DrawFills(threads[run]);
    GPUTestDevice::Destroy();
    files[run] = Files(dir);
  }
  EXPECT_EQ(pixels[0], pixels[1]);
  EXPECT_EQ(files[0], files[1]);
}

TEST_F(PipelineBatchGPU, ParallelPipelinesDrawTheSame)
{
  Error error;
  ASSERT_TRUE(GPUTestDevice::Create({}, &error)) << error.GetDescription();
  const std::vector<u32> one = DrawFills(1);
  const std::vector<u32> eight = DrawFills(8);
  ASSERT_EQ(one.size(), 64u * TARGET_SIZE * TARGET_SIZE);
  EXPECT_EQ(one, eight);

  // The fills did draw: write mask 15 without blending gives the fill colour.
  const u32 opaque = one[15 * TARGET_SIZE * TARGET_SIZE + 5 * TARGET_SIZE + 5];
  EXPECT_NE(opaque, 0xC0402010u);
}

} // namespace
