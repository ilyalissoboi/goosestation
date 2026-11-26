// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/gpu_device.h"
#include "util/shader_compile_threads.h"

#include "core/settings.h"

#include "common/file_system.h"
#include "common/memory_settings_interface.h"
#include "common/path.h"

#include <gtest/gtest.h>

#include <cstdlib>
#include <filesystem>
#include <string>

namespace {

constexpr u64 GIB = 1024ull * 1024ull * 1024ull;

TEST(ShaderCompileThreads, AutomaticRule)
{
  EXPECT_EQ(ShaderCompileThreads::GetAutomatic(8, 12 * GIB), 8u);
  EXPECT_EQ(ShaderCompileThreads::GetAutomatic(8, 3 * GIB), 3u);
  EXPECT_EQ(ShaderCompileThreads::GetAutomatic(2, 16 * GIB), 2u);
  EXPECT_EQ(ShaderCompileThreads::GetAutomatic(1, 0), 1u);
  EXPECT_EQ(ShaderCompileThreads::GetAutomatic(0, 0), 1u);
  EXPECT_EQ(ShaderCompileThreads::GetAutomatic(64, 64 * GIB), ShaderCompileThreads::MAX_AUTOMATIC);
}

TEST(ShaderCompileThreads, PhysicalMemoryIsKnown)
{
  EXPECT_GT(ShaderCompileThreads::GetPhysicalMemory(), 256ull * 1024ull * 1024ull);
}

TEST(ShaderCompileThreads, KnownBadMatchesApiAndDriverText)
{
  static constexpr ShaderCompileThreads::KnownBadDriver list[] = {
    {RenderAPI::Vulkan, "Adreno", "512.7", "test reason"}};
  const std::string vulkan = "Driver 512.7.0\nVulkan 1.3.0\nQualcomm Technologies Inc. Adreno Vulkan Driver\nAdreno (TM) 740";
  const std::optional<std::string> found = ShaderCompileThreads::FindKnownBad(RenderAPI::Vulkan, vulkan, list);
  ASSERT_TRUE(found.has_value());
  EXPECT_NE(found->find("test reason"), std::string::npos);

  EXPECT_FALSE(ShaderCompileThreads::FindKnownBad(RenderAPI::OpenGLES, vulkan, list).has_value());
  EXPECT_FALSE(
    ShaderCompileThreads::FindKnownBad(RenderAPI::Vulkan, "Driver 615.1.0\nAdreno (TM) 740", list).has_value());
}

class ShaderCompileGuard : public ::testing::Test
{
protected:
  void SetUp() override
  {
    std::error_code ec;
    m_dir = (std::filesystem::temp_directory_path(ec) / ("common-tests-guard-" + std::to_string(std::rand())))
              .generic_string();
    ASSERT_TRUE(FileSystem::CreateDirectory(m_dir.c_str(), true));
  }

  void TearDown() override { FileSystem::RecursiveDeleteDirectory(m_dir.c_str()); }

  std::string m_dir;
};

TEST_F(ShaderCompileGuard, AnUnfinishedPassBlocksTheSameDriver)
{
  {
    ShaderCompileThreads::Guard guard(m_dir, "Vulkan", "Vulkan\nDriver 1.2.3", "Vulkan, Test GPU");
    EXPECT_FALSE(guard.GetBlockReason().has_value());
    guard.BeginPass();
    // The app dies here: no EndPass.
  }

  const ShaderCompileThreads::Guard next(m_dir, "Vulkan", "Vulkan\nDriver 1.2.3", "Vulkan, Test GPU");
  const std::optional<std::string> reason = next.GetBlockReason();
  ASSERT_TRUE(reason.has_value());
  EXPECT_NE(reason->find("Vulkan, Test GPU"), std::string::npos);

  const std::optional<std::string> note = ShaderCompileThreads::Guard::ReadNote(m_dir);
  ASSERT_TRUE(note.has_value());
  EXPECT_NE(note->find("Vulkan, Test GPU"), std::string::npos);
}

TEST_F(ShaderCompileGuard, AFinishedPassLeavesNoBlock)
{
  {
    ShaderCompileThreads::Guard guard(m_dir, "Vulkan", "key", "label");
    guard.BeginPass();
    guard.EndPass();
  }
  EXPECT_FALSE(ShaderCompileThreads::Guard(m_dir, "Vulkan", "key", "label").GetBlockReason().has_value());
  EXPECT_FALSE(ShaderCompileThreads::Guard::ReadNote(m_dir).has_value());
}

TEST_F(ShaderCompileGuard, ADriverChangeLiftsTheBlock)
{
  {
    ShaderCompileThreads::Guard guard(m_dir, "Vulkan", "old driver", "label");
    guard.BeginPass();
  }
  EXPECT_TRUE(ShaderCompileThreads::Guard(m_dir, "Vulkan", "old driver", "label").GetBlockReason().has_value());

  EXPECT_FALSE(ShaderCompileThreads::Guard(m_dir, "Vulkan", "new driver", "label").GetBlockReason().has_value());
  EXPECT_FALSE(ShaderCompileThreads::Guard::ReadNote(m_dir).has_value());
  EXPECT_FALSE(ShaderCompileThreads::Guard(m_dir, "Vulkan", "old driver", "label").GetBlockReason().has_value());
}

TEST_F(ShaderCompileGuard, AnotherRendererKeepsTheBlock)
{
  {
    ShaderCompileThreads::Guard guard(m_dir, "Vulkan", "vulkan driver", "Vulkan, Test GPU");
    guard.BeginPass();
  }
  EXPECT_FALSE(ShaderCompileThreads::Guard(m_dir, "OpenGL", "gl driver", "OpenGL, Test GPU").GetBlockReason().has_value());
  EXPECT_TRUE(ShaderCompileThreads::Guard(m_dir, "Vulkan", "vulkan driver", "Vulkan, Test GPU").GetBlockReason().has_value());
  const std::optional<std::string> note = ShaderCompileThreads::Guard::ReadNote(m_dir);
  ASSERT_TRUE(note.has_value());
  EXPECT_EQ(note.value(), "Automatic uses 1 on Vulkan, Test GPU: a build with several threads didn't finish last time.");
}

TEST_F(ShaderCompileGuard, AChosenNumberClearsTheBlock)
{
  {
    ShaderCompileThreads::Guard guard(m_dir, "Vulkan", "key", "label");
    guard.BeginPass();
  }
  {
    ShaderCompileThreads::Guard guard(m_dir, "Vulkan", "key", "label");
    ASSERT_TRUE(guard.GetBlockReason().has_value());
    guard.ClearBlock();
    EXPECT_FALSE(guard.GetBlockReason().has_value());
  }
  EXPECT_FALSE(ShaderCompileThreads::Guard(m_dir, "Vulkan", "key", "label").GetBlockReason().has_value());
  EXPECT_FALSE(ShaderCompileThreads::Guard::ReadNote(m_dir).has_value());
}

TEST_F(ShaderCompileGuard, NoCacheFolderMeansNoGuard)
{
  {
    ShaderCompileThreads::Guard guard(std::string(), "Vulkan", "key", "label");
    guard.BeginPass();
  }
  EXPECT_FALSE(ShaderCompileThreads::Guard(std::string(), "Vulkan", "key", "label").GetBlockReason().has_value());
  EXPECT_FALSE(ShaderCompileThreads::Guard::ReadNote(std::string()).has_value());
}

TEST_F(ShaderCompileGuard, TheNoteNamesTheListFirstThenTheGuard)
{
  {
    ShaderCompileThreads::Guard guard(m_dir, "Vulkan", "key", "Vulkan, Test GPU");
    guard.BeginPass();
  }
  { ShaderCompileThreads::Guard convert(m_dir, "Vulkan", "key", "Vulkan, Test GPU"); }

  ShaderCompileThreads::SetKnownBadNote("Automatic uses 1 on Vulkan, Listed GPU: it's on the list.");
  EXPECT_EQ(ShaderCompileThreads::GetNote(m_dir), "Automatic uses 1 on Vulkan, Listed GPU: it's on the list.");
  ShaderCompileThreads::SetKnownBadNote(std::nullopt);
  EXPECT_EQ(ShaderCompileThreads::GetNote(m_dir),
            "Automatic uses 1 on Vulkan, Test GPU: a build with several threads didn't finish last time.");
}

TEST(GPUSettings, ShaderCompileThreadsRoundTrips)
{
  MemorySettingsInterface empty;
  Settings defaults;
  defaults.gpu_shader_compile_threads = 7;
  defaults.Load(empty, empty);
  EXPECT_EQ(defaults.gpu_shader_compile_threads, 0u);

  Settings four;
  four.gpu_shader_compile_threads = 4;
  MemorySettingsInterface saved;
  four.Save(saved, false);
  EXPECT_EQ(saved.GetIntValue("GPU", "ShaderCompileThreads", -1), 4);
  Settings loaded;
  loaded.Load(saved, saved);
  EXPECT_EQ(loaded.gpu_shader_compile_threads, 4u);

  MemorySettingsInterface too_many;
  too_many.SetIntValue("GPU", "ShaderCompileThreads", 99);
  Settings clamped;
  clamped.Load(too_many, too_many);
  EXPECT_EQ(clamped.gpu_shader_compile_threads, ShaderCompileThreads::MAX_THREADS);
}

} // namespace
