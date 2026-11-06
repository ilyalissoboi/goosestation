// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/librashader_loader.h"
#include "util/postprocessing.h"
#include "util/postprocessing_shader_slang.h"
#include "util/postprocessing_slang_picture.h"

#include "core/settings.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/memory_settings_interface.h"
#include "common/path.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <atomic>
#include <filesystem>
#include <string>
#include <vector>

using namespace PostProcessing::Slang;

namespace {

// A fresh directory under the system's temporary folder, deleted with its contents when the test ends.
class TempDirectory
{
public:
  TempDirectory()
  {
    static std::atomic<int> s_counter{0};
    std::error_code ec;
    const std::filesystem::path base = std::filesystem::temp_directory_path(ec);
    for (int attempt = 0; attempt < 100; attempt++)
    {
      std::filesystem::path p = base / ("common-tests-slang-" + std::to_string(std::rand()) + "-" +
                                        std::to_string(s_counter.fetch_add(1)));
      if (std::filesystem::create_directory(p, ec))
      {
        m_path = p.generic_string();
        break;
      }
    }
  }

  ~TempDirectory()
  {
    if (!m_path.empty())
      FileSystem::RecursiveDeleteDirectory(m_path.c_str());
  }

  const std::string& GetPath() const { return m_path; }

  // Writes a file at a relative path ('/' separators), making its folders.
  void Write(const std::string& relative, std::string_view contents) const
  {
    const std::filesystem::path p = std::filesystem::path(m_path) / relative;
    std::error_code ec;
    std::filesystem::create_directories(p.parent_path(), ec);
    ASSERT_TRUE(FileSystem::WriteStringToFile(p.generic_string().c_str(), contents));
  }

private:
  std::string m_path;
};

// Points the shaders and resources folders at temporary ones for the test's lifetime.
class ScopedShaderFolders
{
public:
  ScopedShaderFolders(const std::string& shaders, const std::string& resources)
    : m_old_shaders(EmuFolders::Shaders), m_old_resources(EmuFolders::Resources)
  {
    EmuFolders::Shaders = shaders;
    EmuFolders::Resources = resources;
  }
  ~ScopedShaderFolders()
  {
    EmuFolders::Shaders = m_old_shaders;
    EmuFolders::Resources = m_old_resources;
  }

private:
  std::string m_old_shaders;
  std::string m_old_resources;
};

bool HasEntry(const std::vector<std::pair<std::string, std::string>>& names, const std::string& display,
              const std::string& name)
{
  return std::find(names.begin(), names.end(), std::make_pair(display, name)) != names.end();
}

} // namespace

TEST(SlangNames, PresetNamesAreRecognisedByExtension)
{
  EXPECT_TRUE(IsPresetName("crt/crt-royale.slangp"));
  EXPECT_TRUE(IsPresetName("A.SLANGP"));
  EXPECT_FALSE(IsPresetName("crt/crt-royale"));
  EXPECT_FALSE(IsPresetName("x.slang"));
  EXPECT_FALSE(IsPresetName(".slangp"));
  EXPECT_FALSE(IsPresetName(""));
}

TEST(SlangNames, ResolveStaysInsideTheShadersFolder)
{
  const std::string root = "/data/shaders";
  EXPECT_EQ(ResolvePresetPath(root, "shaders_slang/crt/a.slangp"), Path::Combine(root, "shaders_slang/crt/a.slangp"));
  EXPECT_EQ(ResolvePresetPath(root, "shaders_slang\\crt\\a.slangp"),
            Path::Combine(root, "shaders_slang/crt/a.slangp"));
  for (const char* bad : {"", "../a.slangp", "x/../../a.slangp", "x/..", "/etc/a.slangp", "C:/a.slangp",
                          "c:a.slangp", "\\\\srv\\a.slangp", "\\a.slangp"})
  {
    EXPECT_EQ(ResolvePresetPath(root, bad), "") << bad;
  }
}

TEST(SlangNames, FindPresetsListsRelativeSortedPathsAndSkipsJunk)
{
  const TempDirectory tmp;
  ASSERT_FALSE(tmp.GetPath().empty());
  tmp.Write("b/z.slangp", "shaders = 0\n");
  tmp.Write("a/y.slangp", "shaders = 0\n");
  tmp.Write("a/y.slang", "#version 450\n");
  tmp.Write("__MACOSX/a/._y.slangp", "junk");
  tmp.Write("__MACOSX/a/w.slangp", "junk");
  tmp.Write(".hidden/q.slangp", "junk");
  tmp.Write("a/.x.slangp", "junk");

  EXPECT_EQ(FindPresets(tmp.GetPath()), (std::vector<std::string>{"a/y.slangp", "b/z.slangp"}));
}

TEST(SlangNames, FindPresetsNeverReadsAPreset)
{
  const TempDirectory tmp;
  tmp.Write("junk.slangp", std::string_view("\0\xff\xfe garbage", 11));
  EXPECT_EQ(FindPresets(tmp.GetPath()), (std::vector<std::string>{"junk.slangp"}));
}

TEST(SlangNames, FindPresetsOfAMissingFolderIsEmpty)
{
  EXPECT_TRUE(FindPresets("/nonexistent/shaders").empty());
}

TEST(SlangNames, AvailableShaderNamesListPresetsOnlyWithTheLibrary)
{
  const TempDirectory shaders, resources;
  shaders.Write("a/y.slangp", "shaders = 0\n");
  const ScopedShaderFolders folders(shaders.GetPath(), resources.GetPath());

  const auto names = PostProcessing::GetAvailableShaderNames();
  if (Librashader::GetAvailability().available)
    EXPECT_TRUE(HasEntry(names, "a/y.slangp [Slang]", "a/y.slangp"));
  else
    EXPECT_TRUE(names.empty());
}

TEST(SlangNames, AddingAPresetWithoutTheLibrarySaysWhy)
{
  if (Librashader::GetAvailability().available)
    GTEST_SKIP() << "librashader is available here";

  const TempDirectory shaders, resources;
  shaders.Write("a/y.slangp", "shaders = 0\n");
  const ScopedShaderFolders folders(shaders.GetPath(), resources.GetPath());

  MemorySettingsInterface si;
  Error error;
  EXPECT_FALSE(PostProcessing::Config::AddStage(si, PostProcessing::Config::DISPLAY_CHAIN_SECTION, "a/y.slangp",
                                                &error));
  EXPECT_EQ(error.GetDescription().rfind("Slang presets aren't available in this build: ", 0), 0u)
    << error.GetDescription();
  EXPECT_EQ(PostProcessing::Config::GetStageCount(si, PostProcessing::Config::DISPLAY_CHAIN_SECTION), 0u);
}

TEST(SlangPicture, NativeSizeIsTheConsolePictureTurnedWithTheDisplay)
{
  EXPECT_EQ(NativeSize(320, 240, false), (PictureSize{320, 240}));
  EXPECT_EQ(NativeSize(320, 240, true), (PictureSize{240, 320}));
  EXPECT_EQ(NativeSize(0, 240, false), (PictureSize{0, 240}));
}

TEST(SlangPicture, BoxFactorOnlyForExactIntegerMultiples)
{
  EXPECT_EQ(BoxFactor(GSVector4i(0, 0, 1280, 960), {320, 240}), 4u);
  EXPECT_EQ(BoxFactor(GSVector4i(16, 8, 16 + 640, 8 + 480), {320, 240}), 2u);
  EXPECT_EQ(BoxFactor(GSVector4i(0, 0, 320, 240), {320, 240}), 0u);
  EXPECT_EQ(BoxFactor(GSVector4i(0, 0, 1104, 720), {368, 240}), 3u);
  EXPECT_EQ(BoxFactor(GSVector4i(0, 0, 1000, 750), {320, 240}), 0u);
  EXPECT_EQ(BoxFactor(GSVector4i(0, 0, 1280, 720), {320, 240}), 0u);
  EXPECT_EQ(BoxFactor(GSVector4i(0, 0, 640, 480), {0, 0}), 0u);
}

TEST(SlangPicture, ClampKeepsThePictureOnTheTarget)
{
  EXPECT_TRUE(ClampToTarget(GSVector4i(-10, 0, 100, 50), 64, 64).eq(GSVector4i(0, 0, 64, 50)));
  EXPECT_TRUE(ClampToTarget(GSVector4i(100, 100, 200, 200), 64, 64).rempty());
}

TEST(SlangPicture, EmptyRectsAndZeroSizesAreNotUsable)
{
  EXPECT_FALSE(IsUsable(GSVector4i(0, 0, 0, 0), {320, 240}));
  EXPECT_FALSE(IsUsable(GSVector4i(0, 0, 64, 64), {0, 240}));
  EXPECT_FALSE(IsUsable(GSVector4i(10, 10, 5, 20), {320, 240}));
  EXPECT_TRUE(IsUsable(GSVector4i(0, 0, 64, 64), {320, 240}));
}
