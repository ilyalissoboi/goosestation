// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "shader_pack_test_utils.h"

#include "util/ini_settings_interface.h"
#include "util/postprocessing.h"
#include "util/postprocessing_favourites.h"

#include "core/settings.h"

#include "common/error.h"
#include "common/memory_settings_interface.h"

#include "fmt/format.h"

#include <gtest/gtest.h>

#include <initializer_list>
#include <string>
#include <vector>

using namespace PostProcessing;
using ShaderPackTest::TempDirectory;

namespace {

constexpr const char* DISPLAY = Config::DISPLAY_CHAIN_SECTION;

// A chain of [names], on, with an option on its first stage.
void WriteChain(SettingsInterface& si, std::initializer_list<const char*> names, bool enabled = true)
{
  si.SetBoolValue(DISPLAY, "Enabled", enabled);
  si.SetUIntValue(DISPLAY, "StageCount", static_cast<u32>(names.size()));
  u32 i = 1;
  for (const char* name : names)
    si.SetStringValue(fmt::format("PostProcessing/Stage{}", i++).c_str(), "ShaderName", name);
  si.SetStringValue("PostProcessing/Stage1", "TEST_GAIN", "1.5");
}

std::vector<std::string> Names(const std::vector<Favourites::Info>& list)
{
  std::vector<std::string> names;
  for (const Favourites::Info& info : list)
    names.push_back(info.name);
  return names;
}

// A favourites folder and settings.ini's order, with a two-stage chain to save.
class PostProcessingFavourites : public ::testing::Test
{
protected:
  std::string Folder() const { return m_dir.Get("postprocessing"); }

  // Saves the chain under name, expecting it to work.
  void SaveAs(std::string_view name, const SettingsInterface* chain = nullptr)
  {
    Error error;
    ASSERT_TRUE(Favourites::Save(Folder(), m_order, name, chain ? *chain : m_chain, false, &error))
      << error.GetDescription();
  }

  std::vector<std::string> Listed() const { return Names(Favourites::List(Folder(), m_order)); }

  void SetUp() override { WriteChain(m_chain, {"crt-lottes", "crt/crt-royale.slangp"}); }

  TempDirectory m_dir;
  MemorySettingsInterface m_order;
  MemorySettingsInterface m_chain;
};

} // namespace

TEST_F(PostProcessingFavourites, SaveWritesTheChainAsSettingsDoes)
{
  SaveAs("Sharp LCD");

  INISettingsInterface file(Path::Combine(Folder(), "Sharp LCD.ini"));
  ASSERT_TRUE(file.Load());
  EXPECT_TRUE(file.GetBoolValue(DISPLAY, "Enabled", false));
  EXPECT_EQ(file.GetUIntValue(DISPLAY, "StageCount", 0u), 2u);
  EXPECT_EQ(file.GetStringValue("PostProcessing/Stage1", "ShaderName"), "crt-lottes");
  EXPECT_EQ(file.GetStringValue("PostProcessing/Stage1", "TEST_GAIN"), "1.5");
  EXPECT_EQ(file.GetStringValue("PostProcessing/Stage2", "ShaderName"), "crt/crt-royale.slangp");
  EXPECT_EQ(file.GetSectionList().size(), 3u);
  EXPECT_EQ(m_order.GetStringList(Favourites::ORDER_SECTION, Favourites::ORDER_KEY),
            std::vector<std::string>{"Sharp LCD"});
}

TEST_F(PostProcessingFavourites, SaveFromADisabledChainEnablesIt)
{
  MemorySettingsInterface off;
  WriteChain(off, {"crt-lottes"}, false);
  SaveAs("Off", &off);

  INISettingsInterface file(Path::Combine(Folder(), "Off.ini"));
  ASSERT_TRUE(file.Load());
  EXPECT_TRUE(file.GetBoolValue(DISPLAY, "Enabled", false));
}

TEST_F(PostProcessingFavourites, SaveRefusesBadNames)
{
  for (const char* bad : {"", "  ", "a/b", "a\\b", ".x", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\tb"})
  {
    Error error;
    EXPECT_FALSE(Favourites::Save(Folder(), m_order, bad, m_chain, false, &error)) << bad;
    EXPECT_FALSE(error.GetDescription().empty()) << bad;
  }

  Error error;
  EXPECT_FALSE(Favourites::Save(Folder(), m_order, " ", m_chain, false, &error));
  EXPECT_EQ(error.GetDescription(), "A favourite needs a name.");
  EXPECT_FALSE(Favourites::Save(Folder(), m_order, "a/b", m_chain, false, &error));
  EXPECT_EQ(error.GetDescription(),
            "A favourite's name can't start with a dot or have / \\ : * ? \" < > | in it.");
  EXPECT_TRUE(Listed().empty());

  SaveAs("  CRT  ");
  EXPECT_EQ(Listed(), std::vector<std::string>{"CRT"});
}

TEST_F(PostProcessingFavourites, SaveRefusesATakenNameIgnoringCase)
{
  SaveAs("CRT");

  Error error;
  EXPECT_FALSE(Favourites::Save(Folder(), m_order, "crt", m_chain, false, &error));
  EXPECT_EQ(error.GetDescription(), "A favourite named crt already exists.");
  EXPECT_EQ(Listed(), std::vector<std::string>{"CRT"});
}

TEST_F(PostProcessingFavourites, ReplaceKeepsPlaceAndCase)
{
  SaveAs("A");
  SaveAs("B");

  MemorySettingsInterface other;
  WriteChain(other, {"sharp"});
  Error error;
  ASSERT_TRUE(Favourites::Save(Folder(), m_order, "a", other, true, &error)) << error.GetDescription();

  EXPECT_EQ(Listed(), (std::vector<std::string>{"A", "B"}));
  INISettingsInterface file(Path::Combine(Folder(), "A.ini"));
  ASSERT_TRUE(file.Load());
  EXPECT_EQ(file.GetUIntValue(DISPLAY, "StageCount", 0u), 1u);
  EXPECT_EQ(file.GetStringValue("PostProcessing/Stage1", "ShaderName"), "sharp");
  EXPECT_FALSE(file.ContainsValue("PostProcessing/Stage2", "ShaderName"));
}

TEST_F(PostProcessingFavourites, FindIgnoresCase)
{
  SaveAs("Sharp LCD");
  EXPECT_EQ(Favourites::Find(Folder(), m_order, "sharp lcd"), "Sharp LCD");
  EXPECT_EQ(Favourites::Find(Folder(), m_order, "Blur"), std::nullopt);
}

TEST_F(PostProcessingFavourites, ListFollowsOrderThenUnorderedFiles)
{
  for (const char* name : {"A", "B", "c", "D"})
    m_dir.Write(fmt::format("postprocessing/{}.ini", name), "[PostProcessing]\nEnabled = true\n");
  m_order.SetStringList(Favourites::ORDER_SECTION, Favourites::ORDER_KEY, {"B", "A"});

  EXPECT_EQ(Listed(), (std::vector<std::string>{"B", "A", "c", "D"}));
}

TEST_F(PostProcessingFavourites, ListDropsOrderNamesWithoutFiles)
{
  SaveAs("A");
  m_order.SetStringList(Favourites::ORDER_SECTION, Favourites::ORDER_KEY, {"Gone", "A"});

  EXPECT_EQ(Listed(), std::vector<std::string>{"A"});

  // The next write of the order leaves it out.
  SaveAs("B");
  EXPECT_EQ(m_order.GetStringList(Favourites::ORDER_SECTION, Favourites::ORDER_KEY),
            (std::vector<std::string>{"A", "B"}));
}

TEST_F(PostProcessingFavourites, ListSaysWhichShadersAreMissing)
{
  const TempDirectory shaders;
  const ShaderPackTest::ScopedShadersFolder scoped(shaders.GetPath());
  shaders.Write("crt-lottes.glsl", "void main() {}");
  SaveAs("CRT");

  const std::vector<Favourites::Info> list = Favourites::List(Folder(), m_order);
  ASSERT_EQ(list.size(), 1u);
  EXPECT_EQ(list[0].shaders, (std::vector<std::string>{"crt-lottes", "crt/crt-royale.slangp"}));
  EXPECT_EQ(list[0].missing, std::vector<std::string>{"crt/crt-royale.slangp"});
  EXPECT_EQ(Favourites::Summary(list[0]), "Not installed: crt/crt-royale.slangp");

  shaders.Write("crt/crt-royale.slangp", "shaders = 0\n");
  const std::vector<Favourites::Info> found = Favourites::List(Folder(), m_order);
  ASSERT_EQ(found.size(), 1u);
  EXPECT_TRUE(found[0].missing.empty());
  EXPECT_EQ(Favourites::Summary(found[0]), "crt-lottes · crt/crt-royale.slangp");
}

TEST_F(PostProcessingFavourites, ListOfAFileWithoutAChainHasNoShaders)
{
  m_dir.Write("postprocessing/Empty.ini", "[Main]\nFoo = 1\n");

  const std::vector<Favourites::Info> list = Favourites::List(Folder(), m_order);
  ASSERT_EQ(list.size(), 1u);
  EXPECT_EQ(list[0].name, "Empty");
  EXPECT_TRUE(list[0].shaders.empty());
  EXPECT_TRUE(list[0].missing.empty());
}

TEST_F(PostProcessingFavourites, ListOfNoFolderIsEmpty)
{
  EXPECT_TRUE(Listed().empty());
}

TEST_F(PostProcessingFavourites, RenameMovesFileAndKeepsPlace)
{
  SaveAs("A");
  SaveAs("B");
  SaveAs("C");

  Error error;
  ASSERT_TRUE(Favourites::Rename(Folder(), m_order, "B", "Sharp LCD", &error)) << error.GetDescription();

  EXPECT_EQ(Listed(), (std::vector<std::string>{"A", "Sharp LCD", "C"}));
  EXPECT_FALSE(m_dir.Exists("postprocessing/B.ini"));
  EXPECT_TRUE(m_dir.Exists("postprocessing/Sharp LCD.ini"));
}

TEST_F(PostProcessingFavourites, RenameRefusesAnotherNamesCase)
{
  SaveAs("A");
  SaveAs("B");

  Error error;
  EXPECT_FALSE(Favourites::Rename(Folder(), m_order, "B", "a", &error));
  EXPECT_EQ(error.GetDescription(), "A favourite named a already exists.");
  EXPECT_FALSE(Favourites::Rename(Folder(), m_order, "B", "x/y", &error));
  EXPECT_FALSE(Favourites::Rename(Folder(), m_order, "Gone", "Here", &error));
  EXPECT_EQ(error.GetDescription(), "There's no favourite named Gone.");
  EXPECT_EQ(Listed(), (std::vector<std::string>{"A", "B"}));
}

TEST_F(PostProcessingFavourites, RenameChangesCaseOnly)
{
  SaveAs("crt");
  SaveAs("Other");

  Error error;
  ASSERT_TRUE(Favourites::Rename(Folder(), m_order, "crt", "CRT", &error)) << error.GetDescription();

  EXPECT_EQ(Listed(), (std::vector<std::string>{"CRT", "Other"}));
  FileSystem::FindResultsArray files;
  FileSystem::FindFiles(Folder().c_str(), "*.ini", FILESYSTEM_FIND_FILES, &files);
  ASSERT_EQ(files.size(), 2u);
  const bool has_upper = std::any_of(files.begin(), files.end(), [](const FILESYSTEM_FIND_DATA& fd) {
    return Path::GetFileName(fd.FileName) == "CRT.ini";
  });
  EXPECT_TRUE(has_upper);
}

TEST_F(PostProcessingFavourites, MoveSwapsAndStopsAtEnds)
{
  SaveAs("A");
  SaveAs("B");
  SaveAs("C");

  Favourites::Move(Folder(), m_order, "C", false);
  EXPECT_EQ(Listed(), (std::vector<std::string>{"A", "C", "B"}));
  Favourites::Move(Folder(), m_order, "a", true);
  EXPECT_EQ(Listed(), (std::vector<std::string>{"C", "A", "B"}));
  // C is first and B last: neither goes past its end.
  Favourites::Move(Folder(), m_order, "C", false);
  Favourites::Move(Folder(), m_order, "B", true);
  Favourites::Move(Folder(), m_order, "Gone", true);
  EXPECT_EQ(Listed(), (std::vector<std::string>{"C", "A", "B"}));
}

TEST_F(PostProcessingFavourites, DeleteRemovesFileAndOrderEntry)
{
  SaveAs("A");
  SaveAs("B");

  Error error;
  ASSERT_TRUE(Favourites::Delete(Folder(), m_order, "a", &error)) << error.GetDescription();

  EXPECT_EQ(Listed(), std::vector<std::string>{"B"});
  EXPECT_FALSE(m_dir.Exists("postprocessing/A.ini"));
  EXPECT_EQ(m_order.GetStringList(Favourites::ORDER_SECTION, Favourites::ORDER_KEY), std::vector<std::string>{"B"});
}

TEST_F(PostProcessingFavourites, DeleteOfAMissingNameFails)
{
  Error error;
  EXPECT_FALSE(Favourites::Delete(Folder(), m_order, "x", &error));
  EXPECT_EQ(error.GetDescription(), "There's no favourite named x.");
}

TEST_F(PostProcessingFavourites, ApplyReplacesTheChainAndEnablesIt)
{
  MemorySettingsInterface one;
  WriteChain(one, {"sharp"});
  SaveAs("One", &one);

  MemorySettingsInterface to;
  WriteChain(to, {"a", "b", "c"}, false);
  to.SetStringValue("PostProcessing/Stage3", "OPT", "2");
  to.SetBoolValue(Config::INTERNAL_CHAIN_SECTION, "Enabled", true);
  to.SetUIntValue(Config::INTERNAL_CHAIN_SECTION, "StageCount", 1);
  to.SetStringValue("InternalPostProcessing/Stage1", "ShaderName", "internal");
  to.SetIntValue("Main", "Foo", 1);

  Error error;
  ASSERT_TRUE(Favourites::Apply(Folder(), "one", to, &error)) << error.GetDescription();

  EXPECT_TRUE(to.GetBoolValue(DISPLAY, "Enabled", false));
  EXPECT_EQ(to.GetUIntValue(DISPLAY, "StageCount", 0u), 1u);
  EXPECT_EQ(to.GetStringValue("PostProcessing/Stage1", "ShaderName"), "sharp");
  EXPECT_TRUE(to.GetKeyValueList("PostProcessing/Stage2").empty());
  EXPECT_TRUE(to.GetKeyValueList("PostProcessing/Stage3").empty());
  EXPECT_EQ(to.GetStringValue("InternalPostProcessing/Stage1", "ShaderName"), "internal");
  EXPECT_EQ(to.GetIntValue("Main", "Foo", 0), 1);
}

TEST_F(PostProcessingFavourites, ApplyOfAMissingNameFails)
{
  MemorySettingsInterface to;
  WriteChain(to, {"a"});
  Error error;
  EXPECT_FALSE(Favourites::Apply(Folder(), "Gone", to, &error));
  EXPECT_EQ(error.GetDescription(), "There's no favourite named Gone.");
  EXPECT_EQ(to.GetStringValue("PostProcessing/Stage1", "ShaderName"), "a");
}

TEST_F(PostProcessingFavourites, ApplyKeepsAMissingShader)
{
  const TempDirectory shaders;
  const ShaderPackTest::ScopedShadersFolder scoped(shaders.GetPath());
  SaveAs("CRT");

  MemorySettingsInterface to;
  Error error;
  ASSERT_TRUE(Favourites::Apply(Folder(), "CRT", to, &error)) << error.GetDescription();
  EXPECT_EQ(to.GetStringValue("PostProcessing/Stage2", "ShaderName"), "crt/crt-royale.slangp");
}

TEST_F(PostProcessingFavourites, ApplyOfAFileWithoutAChainEmptiesTheChain)
{
  m_dir.Write("postprocessing/Empty.ini", "[Main]\nFoo = 1\n");
  MemorySettingsInterface to;
  WriteChain(to, {"a"});

  Error error;
  ASSERT_TRUE(Favourites::Apply(Folder(), "Empty", to, &error)) << error.GetDescription();
  EXPECT_EQ(Config::GetStageCount(to, DISPLAY), 0u);
  EXPECT_TRUE(to.GetBoolValue(DISPLAY, "Enabled", false));
  EXPECT_FALSE(to.ContainsValue("Main", "Foo"));
}

TEST(PostProcessingFavouritesChains, ChainsEqualIgnoresEnabledAndKeyOrder)
{
  MemorySettingsInterface a, b;
  WriteChain(a, {"x", "y"}, true);
  b.SetStringValue("PostProcessing/Stage1", "TEST_GAIN", "1.5");
  b.SetStringValue("PostProcessing/Stage2", "ShaderName", "y");
  b.SetStringValue("PostProcessing/Stage1", "ShaderName", "x");
  b.SetUIntValue(DISPLAY, "StageCount", 2);
  b.SetBoolValue(DISPLAY, "Enabled", false);

  EXPECT_TRUE(Favourites::ChainsEqual(a, b));
}

TEST(PostProcessingFavouritesChains, ChainsEqualSeesAnOption)
{
  MemorySettingsInterface a, b;
  WriteChain(a, {"x"});
  WriteChain(b, {"x"});
  b.SetStringValue("PostProcessing/Stage1", "TEST_GAIN", "2.0");
  EXPECT_FALSE(Favourites::ChainsEqual(a, b));

  MemorySettingsInterface c;
  WriteChain(c, {"x", "y"});
  EXPECT_FALSE(Favourites::ChainsEqual(a, c));
}

namespace {

// Saves A, B and C, each a chain of its own name (or of shared, for those in same).
void SaveThree(const std::string& folder, SettingsInterface& order, std::initializer_list<const char*> same = {})
{
  for (const char* name : {"A", "B", "C"})
  {
    MemorySettingsInterface chain;
    const bool shared = std::find_if(same.begin(), same.end(), [name](const char* s) {
                          return std::string_view(s) == name;
                        }) != same.end();
    WriteChain(chain, {shared ? "shared" : name});
    Error error;
    ASSERT_TRUE(Favourites::Save(folder, order, name, chain, false, &error)) << error.GetDescription();
  }
}

MemorySettingsInterface ChainOf(const char* shader)
{
  MemorySettingsInterface chain;
  WriteChain(chain, {shader});
  return chain;
}

} // namespace

TEST_F(PostProcessingFavourites, StepWrapsBothWays)
{
  SaveThree(Folder(), m_order);

  EXPECT_EQ(Favourites::Step(Folder(), m_order, ChainOf("B"), "", true), "C");
  EXPECT_EQ(Favourites::Step(Folder(), m_order, ChainOf("B"), "", false), "A");
  EXPECT_EQ(Favourites::Step(Folder(), m_order, ChainOf("C"), "", true), "A");
  EXPECT_EQ(Favourites::Step(Folder(), m_order, ChainOf("A"), "", false), "C");
}

TEST_F(PostProcessingFavourites, StepFromNoMatchGoesToEnds)
{
  SaveThree(Folder(), m_order);

  // An edited chain, and a remembered favourite it no longer equals.
  EXPECT_EQ(Favourites::Step(Folder(), m_order, ChainOf("edited"), "B", true), "A");
  EXPECT_EQ(Favourites::Step(Folder(), m_order, ChainOf("edited"), "B", false), "C");
}

TEST_F(PostProcessingFavourites, StepWithNoFavouritesIsNullopt)
{
  EXPECT_EQ(Favourites::Step(Folder(), m_order, ChainOf("A"), "", true), std::nullopt);
}

TEST_F(PostProcessingFavourites, StepComesRoundIdenticalFavourites)
{
  SaveThree(Folder(), m_order, {"A", "B"});

  EXPECT_EQ(Favourites::Step(Folder(), m_order, ChainOf("shared"), "", true), "B");
  EXPECT_EQ(Favourites::Step(Folder(), m_order, ChainOf("shared"), "A", true), "B");
  EXPECT_EQ(Favourites::Step(Folder(), m_order, ChainOf("shared"), "b", true), "C");
  EXPECT_EQ(Favourites::Step(Folder(), m_order, ChainOf("shared"), "B", false), "A");
}

TEST_F(PostProcessingFavourites, StepChainWritesTheBaseWhenTheGameUsesTheGlobalChain)
{
  SaveThree(Folder(), m_order);
  WriteChain(m_order, {"A"});
  const std::string game_file = m_dir.Get("gamesettings/SLUS-00001.ini");
  m_dir.Write("gamesettings/SLUS-00001.ini", "[Main]\nFoo = 1\n");
  INISettingsInterface game_layer(game_file);
  ASSERT_TRUE(game_layer.Load());

  std::string last;
  Error error;
  EXPECT_EQ(Favourites::StepChain(Folder(), m_order, &game_layer, game_file, &last, true, &error), "B");

  EXPECT_EQ(m_order.GetStringValue("PostProcessing/Stage1", "ShaderName"), "B");
  EXPECT_EQ(last, "B");
  EXPECT_EQ(m_dir.Read("gamesettings/SLUS-00001.ini"), "[Main]\nFoo = 1\n");

  // No game at all.
  EXPECT_EQ(Favourites::StepChain(Folder(), m_order, nullptr, std::string(), &last, true, &error), "C");
  EXPECT_EQ(m_order.GetStringValue("PostProcessing/Stage1", "ShaderName"), "C");
}

TEST_F(PostProcessingFavourites, StepChainWritesTheGameFileWhenItHasItsOwnChain)
{
  SaveThree(Folder(), m_order);
  WriteChain(m_order, {"A"});
  const std::string game_file = m_dir.Get("gamesettings/SLUS-00001.ini");
  m_dir.Write("gamesettings/SLUS-00001.ini",
              "[Main]\nFoo = 1\n\n[PostProcessing]\nEnabled = false\nStageCount = 1\n\n"
              "[PostProcessing/Stage1]\nShaderName = B\nTEST_GAIN = 1.5\n\n[InternalPostProcessing]\nEnabled = true\n");
  INISettingsInterface game_layer(game_file);
  ASSERT_TRUE(game_layer.Load());

  std::string last;
  Error error;
  // The game runs B's chain (settings.ini's is A's): next is C, into the game's file.
  EXPECT_EQ(Favourites::StepChain(Folder(), m_order, &game_layer, game_file, &last, true, &error), "C");
  EXPECT_EQ(last, "C");
  INISettingsInterface written(game_file);
  ASSERT_TRUE(written.Load());
  EXPECT_EQ(written.GetIntValue("Main", "Foo", 0), 1);
  EXPECT_TRUE(written.GetBoolValue(Config::INTERNAL_CHAIN_SECTION, "Enabled", false));
  EXPECT_TRUE(written.GetBoolValue(DISPLAY, "Enabled", false));
  EXPECT_EQ(written.GetUIntValue(DISPLAY, "StageCount", 0u), 1u);
  EXPECT_EQ(written.GetStringValue("PostProcessing/Stage1", "ShaderName"), "C");
  EXPECT_EQ(m_order.GetStringValue("PostProcessing/Stage1", "ShaderName"), "A");
}

TEST_F(PostProcessingFavourites, StepChainWithNoFavourites)
{
  WriteChain(m_order, {"A"});
  std::string last;
  Error error;
  EXPECT_EQ(Favourites::StepChain(Folder(), m_order, nullptr, std::string(), &last, true, &error), std::nullopt);
  EXPECT_TRUE(error.GetDescription().empty());
  EXPECT_TRUE(last.empty());
  EXPECT_EQ(m_order.GetStringValue("PostProcessing/Stage1", "ShaderName"), "A");
}
