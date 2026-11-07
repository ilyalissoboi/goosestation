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

namespace {

// A preset with two parameters, whose shader is valid GLSL, so it also runs in a real chain (from the PCSX2 fork's
// tests).
constexpr const char* TEST_PRESET = "shaders = 1\nshader0 = params.slang\n";
constexpr const char* TEST_SHADER =
  "#version 450\n"
  "#pragma parameter TEST_GAIN \"Test gain\" 1.0 0.0 2.0 0.1\n"
  "#pragma parameter TEST_FLAG \"Test flag\" 0.0 0.0 1.0 1.0\n"
  "layout(push_constant) uniform Push { float TEST_GAIN; float TEST_FLAG; } params;\n"
  "layout(std140, set = 0, binding = 0) uniform UBO { mat4 MVP; } global;\n"
  "#pragma stage vertex\n"
  "layout(location = 0) in vec4 Position;\n"
  "layout(location = 1) in vec2 TexCoord;\n"
  "layout(location = 0) out vec2 vTexCoord;\n"
  "void main() { gl_Position = global.MVP * Position; vTexCoord = TexCoord; }\n"
  "#pragma stage fragment\n"
  "layout(location = 0) in vec2 vTexCoord;\n"
  "layout(location = 0) out vec4 FragColor;\n"
  "layout(set = 0, binding = 2) uniform sampler2D Source;\n"
  "void main() { FragColor = texture(Source, vTexCoord) * params.TEST_GAIN; }\n";

// The test preset at p/test.slangp in a temporary shaders folder.
struct TestPresetFolders
{
  TempDirectory shaders, resources;
  ScopedShaderFolders folders{shaders.GetPath(), resources.GetPath()};

  TestPresetFolders()
  {
    shaders.Write("p/test.slangp", TEST_PRESET);
    shaders.Write("p/params.slang", TEST_SHADER);
  }
};

#define SKIP_WITHOUT_LIBRASHADER()                                                                                     \
  if (!Librashader::GetAvailability().available)                                                                      \
  GTEST_SKIP() << "librashader isn't loadable (" << Librashader::GetAvailability().reason                            \
               << "); set GOOSESTATION_LIBRASHADER_PATH to run this test"

} // namespace

TEST(SlangStage, OptionsAreThePresetsParameters)
{
  SKIP_WITHOUT_LIBRASHADER();
  const TestPresetFolders f;

  Error error;
  const std::vector<PostProcessing::ShaderOption> options =
    PostProcessing::Config::GetShaderOptions("p/test.slangp", &error);
  ASSERT_EQ(options.size(), 2u) << error.GetDescription();

  EXPECT_EQ(options[0].name, "TEST_GAIN");
  EXPECT_EQ(options[0].ui_name, "Test gain");
  EXPECT_EQ(options[0].type, PostProcessing::ShaderOption::Type::Float);
  EXPECT_EQ(options[0].vector_size, 1u);
  EXPECT_FLOAT_EQ(options[0].default_value[0].float_value, 1.0f);
  EXPECT_FLOAT_EQ(options[0].value[0].float_value, 1.0f);
  EXPECT_FLOAT_EQ(options[0].min_value[0].float_value, 0.0f);
  EXPECT_FLOAT_EQ(options[0].max_value[0].float_value, 2.0f);
  EXPECT_FLOAT_EQ(options[0].step_value[0].float_value, 0.1f);

  EXPECT_EQ(options[1].name, "TEST_FLAG");
  EXPECT_FLOAT_EQ(options[1].default_value[0].float_value, 0.0f);
  EXPECT_FLOAT_EQ(options[1].max_value[0].float_value, 1.0f);
  EXPECT_FLOAT_EQ(options[1].step_value[0].float_value, 1.0f);
}

TEST(SlangStage, StoredOptionsComeBackAsValues)
{
  SKIP_WITHOUT_LIBRASHADER();
  const TestPresetFolders f;
  const char* section = PostProcessing::Config::DISPLAY_CHAIN_SECTION;

  MemorySettingsInterface si;
  Error error;
  ASSERT_TRUE(PostProcessing::Config::AddStage(si, section, "p/test.slangp", &error)) << error.GetDescription();
  EXPECT_EQ(si.GetStringValue("PostProcessing/Stage1", "ShaderName"), "p/test.slangp");

  std::vector<PostProcessing::ShaderOption> options = PostProcessing::Config::GetStageOptions(si, section, 0);
  ASSERT_EQ(options.size(), 2u);
  options[0].value[0].float_value = 1.5f;
  PostProcessing::Config::SetStageOption(si, section, 0, options[0]);
  EXPECT_EQ(si.GetStringValue("PostProcessing/Stage1", "TEST_GAIN"), "1.5");

  options = PostProcessing::Config::GetStageOptions(si, section, 0);
  ASSERT_EQ(options.size(), 2u);
  EXPECT_FLOAT_EQ(options[0].value[0].float_value, 1.5f);
  EXPECT_FLOAT_EQ(options[1].value[0].float_value, 0.0f);
}

TEST(SlangStage, ABrokenPresetFailsWithLibrashadersText)
{
  SKIP_WITHOUT_LIBRASHADER();
  const TestPresetFolders f;
  f.shaders.Write("p/broken.slangp", "shaders = 1\nshader0 = does_not_exist.slang\n");

  MemorySettingsInterface si;
  Error error;
  EXPECT_FALSE(PostProcessing::Config::AddStage(si, PostProcessing::Config::DISPLAY_CHAIN_SECTION, "p/broken.slangp",
                                                &error));
  EXPECT_NE(error.GetDescription().find("does_not_exist"), std::string::npos) << error.GetDescription();
}

TEST(SlangStage, ChangedOptionsAreMarkedForTheRunningChain)
{
  SKIP_WITHOUT_LIBRASHADER();
  const TestPresetFolders f;

  PostProcessing::SlangShader shader;
  Error error;
  ASSERT_TRUE(shader.LoadFromFile("p/test.slangp", Path::Combine(f.shaders.GetPath(), "p/test.slangp"), false, &error))
    << error.GetDescription();
  EXPECT_TRUE(shader.GetParametersToPush().empty());

  MemorySettingsInterface si;
  si.SetStringValue("PostProcessing/Stage1", "TEST_GAIN", "1.5");
  si.SetStringValue("PostProcessing/Stage1", "TEST_FLAG", "0");
  shader.LoadOptions(si, "PostProcessing/Stage1");
  EXPECT_EQ(shader.GetParametersToPush(), (std::vector<std::string>{"TEST_GAIN"}));
}

TEST(SlangPicture, AWholePictureOnTheTargetIsKept)
{
  const VisiblePicture v = CropToTarget(GSVector4i(160, 0, 1120, 720), GSVector4i(0, 0, 1280, 960), {320, 240}, 1280,
                                        720);
  EXPECT_TRUE(v.out.eq(GSVector4i(160, 0, 1120, 720)));
  EXPECT_TRUE(v.src.eq(GSVector4i(0, 0, 1280, 960)));
  EXPECT_EQ(v.native, (PictureSize{320, 240}));
}

TEST(SlangPicture, APictureLargerThanTheTargetIsCroppedNotSqueezed)
{
  // Integer scaling in a small window: a 1280x960 picture centred on a 960x720 target.
  const VisiblePicture v = CropToTarget(GSVector4i(-160, -120, 1120, 840), GSVector4i(0, 0, 1280, 960), {320, 240},
                                        960, 720);
  EXPECT_TRUE(v.out.eq(GSVector4i(0, 0, 960, 720)));
  EXPECT_TRUE(v.src.eq(GSVector4i(160, 120, 1120, 840)));
  EXPECT_EQ(v.native, (PictureSize{240, 180}));
}

TEST(SlangPicture, ASourceInTheTargetsOwnCoordinatesIsCroppedTheSameWay)
{
  // After other stages, the stage reads the picture out of its input, which is the target's size.
  const VisiblePicture v = CropToTarget(GSVector4i(-160, -120, 1120, 840), GSVector4i(-160, -120, 1120, 840),
                                        {320, 240}, 960, 720);
  EXPECT_TRUE(v.src.eq(GSVector4i(0, 0, 960, 720)));
  EXPECT_EQ(v.native, (PictureSize{240, 180}));
}

TEST(SlangPicture, APictureOffTheTargetHasNothingVisible)
{
  const VisiblePicture v = CropToTarget(GSVector4i(2000, 0, 2640, 480), GSVector4i(0, 0, 640, 480), {320, 240}, 1280,
                                        720);
  EXPECT_TRUE(v.out.rempty());
  EXPECT_FALSE(IsUsable(v.out, v.native));
}
