// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Building shaders and pipelines on several threads, on a real GPU device (opt-in: see gpu_test_device.h).

#include "gpu_test_device.h"

#include "core/gpu_hw.h"
#include "core/gpu_hw_shadergen.h"

#include "util/gpu_device.h"

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

} // namespace
