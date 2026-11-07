// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/postprocessing.h"
#include "util/postprocessing_browser.h"

#include <gtest/gtest.h>

#include <string>
#include <vector>

using PostProcessing::AvailableShader;
using PostProcessing::ShaderKind;
namespace Browser = PostProcessing::Browser;

namespace {

// In GetAvailableShaders' order (by browser path, ignoring case).
const std::vector<AvailableShader> SHADERS = {
  {"a", ShaderKind::GLSL},
  {"crt/ntsc/y.slangp", ShaderKind::Slang},
  {"crt/x.slangp", ShaderKind::Slang},
  {"c", ShaderKind::ReShade},
};

std::vector<std::string> Names(const std::vector<size_t>& indices)
{
  std::vector<std::string> names;
  for (const size_t i : indices)
    names.push_back(SHADERS[i].name);
  return names;
}

} // namespace

TEST(ShaderBrowser, ListShowsChildFoldersThenShaders)
{
  const Browser::Listing top = Browser::List(SHADERS, "");
  EXPECT_EQ(top.folders, (std::vector<std::string>{"crt", "reshade"}));
  EXPECT_EQ(Names(top.shaders), (std::vector<std::string>{"a"}));

  const Browser::Listing crt = Browser::List(SHADERS, "crt");
  EXPECT_EQ(crt.folders, (std::vector<std::string>{"ntsc"}));
  EXPECT_EQ(Names(crt.shaders), (std::vector<std::string>{"crt/x.slangp"}));

  const Browser::Listing reshade = Browser::List(SHADERS, "reshade");
  EXPECT_TRUE(reshade.folders.empty());
  EXPECT_EQ(Names(reshade.shaders), (std::vector<std::string>{"c"}));

  const Browser::Listing none = Browser::List(SHADERS, "nothing/here");
  EXPECT_TRUE(none.folders.empty());
  EXPECT_TRUE(none.shaders.empty());
}

TEST(ShaderBrowser, FoldersAreSortedIgnoringCase)
{
  const std::vector<AvailableShader> shaders = {
    {"Zebra/a.slangp", ShaderKind::Slang}, {"apple/b.slangp", ShaderKind::Slang}, {"Mango/c.slangp", ShaderKind::Slang}};
  EXPECT_EQ(Browser::List(shaders, "").folders, (std::vector<std::string>{"apple", "Mango", "Zebra"}));
}

TEST(ShaderBrowser, SearchMatchesAnywhereInThePathIgnoringCase)
{
  EXPECT_EQ(Names(Browser::Search(SHADERS, "CRT")), (std::vector<std::string>{"crt/ntsc/y.slangp", "crt/x.slangp"}));
  EXPECT_EQ(Names(Browser::Search(SHADERS, " ntsc ")), (std::vector<std::string>{"crt/ntsc/y.slangp"}));
  EXPECT_EQ(Names(Browser::Search(SHADERS, "reshade/")), (std::vector<std::string>{"c"}));
  EXPECT_TRUE(Browser::Search(SHADERS, "  ").empty());
  EXPECT_TRUE(Browser::Search(SHADERS, "").empty());
  EXPECT_TRUE(Browser::Search(SHADERS, "royale").empty());
}

TEST(ShaderBrowser, ParentAndFolderOf)
{
  EXPECT_EQ(Browser::Parent("crt/ntsc"), "crt");
  EXPECT_EQ(Browser::Parent("crt"), "");
  EXPECT_EQ(Browser::Parent(""), "");
  EXPECT_EQ(Browser::FolderOf("crt/ntsc/y.slangp"), "crt/ntsc");
  EXPECT_EQ(Browser::FolderOf("a"), "");
}

TEST(ShaderBrowser, LeafNamesDropThePresetExtension)
{
  EXPECT_EQ(Browser::LeafName(SHADERS[2]), "x");
  EXPECT_EQ(Browser::LeafName(SHADERS[0]), "a");
  EXPECT_EQ(Browser::LeafName({"dolphinfx/crt/zfast", ShaderKind::DolphinFX}), "zfast");
  EXPECT_EQ(Browser::LeafName({"shaders_slang/crt/crt-royale.SLANGP", ShaderKind::Slang}), "crt-royale");
}

TEST(ShaderBrowser, OpenFallsBackToTheNearestFolder)
{
  EXPECT_EQ(Browser::OpenFolder(SHADERS, "crt/ntsc"), "crt/ntsc");
  EXPECT_EQ(Browser::OpenFolder(SHADERS, "crt/gone"), "crt");
  EXPECT_EQ(Browser::OpenFolder(SHADERS, "gone/deeper"), "");
  EXPECT_EQ(Browser::OpenFolder(SHADERS, "reshade"), "reshade");
  EXPECT_EQ(Browser::OpenFolder(SHADERS, ""), "");
  EXPECT_EQ(Browser::OpenFolder({}, "crt"), "");
}
