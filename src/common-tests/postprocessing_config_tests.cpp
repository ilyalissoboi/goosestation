// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/postprocessing.h"

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
