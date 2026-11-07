// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "shader_pack_test_utils.h"

#include "util/postprocessing.h"

#include "core/settings.h"

#include "common/memory_settings_interface.h"

#include "fmt/format.h"

#include <gtest/gtest.h>

using namespace PostProcessing;

namespace {

constexpr const char* DISPLAY = Config::DISPLAY_CHAIN_SECTION;

// A chain of [names], on, with an option on its first stage.
void WriteChain(SettingsInterface& si, std::initializer_list<const char*> names)
{
  si.SetBoolValue(DISPLAY, "Enabled", true);
  si.SetUIntValue(DISPLAY, "StageCount", static_cast<u32>(names.size()));
  u32 i = 1;
  for (const char* name : names)
    si.SetStringValue(fmt::format("PostProcessing/Stage{}", i++).c_str(), "ShaderName", name);
  si.SetStringValue("PostProcessing/Stage1", "TEST_GAIN", "1.5");
}

// Points the shaders folder at a temporary one, with no packs folder or resources, for the test's lifetime.
class ScopedShadersFolder
{
public:
  explicit ScopedShadersFolder(const std::string& shaders)
    : m_old_shaders(EmuFolders::Shaders), m_old_packs(EmuFolders::ShaderPacks), m_old_resources(EmuFolders::Resources)
  {
    EmuFolders::Shaders = shaders;
    EmuFolders::ShaderPacks.clear();
    EmuFolders::Resources = Path::Combine(shaders, "no-resources");
  }
  ~ScopedShadersFolder()
  {
    EmuFolders::Shaders = m_old_shaders;
    EmuFolders::ShaderPacks = m_old_packs;
    EmuFolders::Resources = m_old_resources;
  }

private:
  std::string m_old_shaders;
  std::string m_old_packs;
  std::string m_old_resources;
};

} // namespace

TEST(PostProcessingConfig, CopyChainCopiesStagesAndOptions)
{
  MemorySettingsInterface from, to;
  WriteChain(from, {"crt/crt-royale.slangp", "crt-lottes"});
  to.SetUIntValue(DISPLAY, "StageCount", 3);
  to.SetStringValue("PostProcessing/Stage3", "ShaderName", "stale");

  Config::CopyChain(from, to, DISPLAY);

  EXPECT_TRUE(to.GetBoolValue(DISPLAY, "Enabled", false));
  EXPECT_EQ(Config::GetStageCount(to, DISPLAY), 2u);
  EXPECT_EQ(Config::GetStageShaderName(to, DISPLAY, 0), "crt/crt-royale.slangp");
  EXPECT_EQ(Config::GetStageShaderName(to, DISPLAY, 1), "crt-lottes");
  EXPECT_EQ(to.GetStringValue("PostProcessing/Stage1", "TEST_GAIN"), "1.5");
  EXPECT_FALSE(to.ContainsValue("PostProcessing/Stage3", "ShaderName"));
}

TEST(PostProcessingConfig, CopyChainOfAnEmptyChainLeavesItOff)
{
  MemorySettingsInterface from, to;
  WriteChain(to, {"crt-lottes"});

  Config::CopyChain(from, to, DISPLAY);

  EXPECT_TRUE(to.ContainsValue(DISPLAY, "Enabled"));
  EXPECT_FALSE(to.GetBoolValue(DISPLAY, "Enabled", true));
  EXPECT_EQ(Config::GetStageCount(to, DISPLAY), 0u);
  EXPECT_FALSE(to.ContainsValue("PostProcessing/Stage1", "ShaderName"));
}

TEST(PostProcessingConfig, DeleteChainLeavesNoKeys)
{
  MemorySettingsInterface si;
  WriteChain(si, {"crt-lottes", "simple-flip"});

  Config::DeleteChain(si, DISPLAY);

  EXPECT_FALSE(si.ContainsValue(DISPLAY, "Enabled"));
  EXPECT_FALSE(si.ContainsValue(DISPLAY, "StageCount"));
  EXPECT_FALSE(si.ContainsValue("PostProcessing/Stage1", "ShaderName"));
  EXPECT_FALSE(si.ContainsValue("PostProcessing/Stage2", "ShaderName"));
}

TEST(PostProcessingConfig, HasOwnChainIsEnabledBeingSet)
{
  MemorySettingsInterface si;
  EXPECT_FALSE(Config::HasOwnChain(si, DISPLAY));

  si.SetUIntValue(DISPLAY, "StageCount", 1);
  EXPECT_FALSE(Config::HasOwnChain(si, DISPLAY));

  si.SetBoolValue(DISPLAY, "Enabled", false);
  EXPECT_TRUE(Config::HasOwnChain(si, DISPLAY));
  EXPECT_FALSE(Config::HasOwnChain(si, Config::INTERNAL_CHAIN_SECTION));
}

TEST(PostProcessingConfig, IsShaderInstalledFindsUserGlsl)
{
  const ShaderPackTest::TempDirectory shaders;
  const ScopedShadersFolder folder(shaders.GetPath());
  shaders.Write("crt/a.glsl", "void main() {}");

  EXPECT_TRUE(IsShaderInstalled("crt/a"));
  EXPECT_FALSE(IsShaderInstalled("crt/b"));
}

TEST(PostProcessingConfig, IsShaderInstalledFindsUserReShade)
{
  const ShaderPackTest::TempDirectory shaders;
  const ScopedShadersFolder folder(shaders.GetPath());
  shaders.Write("reshade/Shaders/Sharpen.fx", "");

  EXPECT_TRUE(IsShaderInstalled("Sharpen"));
  EXPECT_FALSE(IsShaderInstalled("Blur"));
}

TEST(PostProcessingConfig, IsShaderInstalledFindsSlangPreset)
{
  const ShaderPackTest::TempDirectory shaders;
  const ScopedShadersFolder folder(shaders.GetPath());
  shaders.Write("x/p.slangp", "shaders = 0\n");

  EXPECT_TRUE(IsShaderInstalled("x/p.slangp"));
  EXPECT_FALSE(IsShaderInstalled("x/q.slangp"));
}

TEST(PostProcessingConfig, FavouritesFolderIsInTheDataFolder)
{
  const ShaderPackTest::TempDirectory data;
  const std::string old_data = EmuFolders::DataRoot;
  // As the hosts set it: LoadConfig() resolves each folder's real path.
  EmuFolders::DataRoot = Path::RealPath(data.GetPath());
  EmuFolders::SetDefaults();
  EXPECT_EQ(EmuFolders::PostProcessing, Path::Combine(EmuFolders::DataRoot, "postprocessing"));

  MemorySettingsInterface si;
  si.SetStringValue("Folders", "PostProcessing", "elsewhere");
  EmuFolders::LoadConfig(si);
  EXPECT_TRUE(EmuFolders::PostProcessing.ends_with("elsewhere")) << EmuFolders::PostProcessing;

  MemorySettingsInterface saved;
  EmuFolders::Save(saved);
  EXPECT_EQ(saved.GetStringValue("Folders", "PostProcessing"), "elsewhere");

  EmuFolders::DataRoot = old_data;
  EmuFolders::SetDefaults();
}
