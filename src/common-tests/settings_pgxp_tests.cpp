// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/settings.h"

#include "common/memory_settings_interface.h"

#include <gtest/gtest.h>

// Big Picture's Depth Clear Threshold row used to write its number into [GPU] PGXPDepthBuffer.

TEST(SettingsPGXP, NumberInDepthBufferMovesToThreshold)
{
  MemorySettingsInterface si;
  si.SetStringValue("GPU", "PGXPDepthBuffer", "450.000000");

  EXPECT_TRUE(Settings::MovePGXPDepthThresholdOutOfDepthBuffer(si));
  EXPECT_FLOAT_EQ(si.GetFloatValue("GPU", "PGXPDepthClearThreshold", 0.0f), 450.0f);
  ASSERT_TRUE(si.ContainsValue("GPU", "PGXPDepthBuffer"));
  EXPECT_FALSE(si.GetBoolValue("GPU", "PGXPDepthBuffer", true));
}

TEST(SettingsPGXP, ExistingThresholdIsKept)
{
  MemorySettingsInterface si;
  si.SetStringValue("GPU", "PGXPDepthBuffer", "450.000000");
  si.SetStringValue("GPU", "PGXPDepthClearThreshold", "120");

  EXPECT_TRUE(Settings::MovePGXPDepthThresholdOutOfDepthBuffer(si));
  EXPECT_FLOAT_EQ(si.GetFloatValue("GPU", "PGXPDepthClearThreshold", 0.0f), 120.0f);
  EXPECT_FALSE(si.GetBoolValue("GPU", "PGXPDepthBuffer", true));
}

TEST(SettingsPGXP, BoolDepthBufferIsLeftAlone)
{
  MemorySettingsInterface si;
  EXPECT_FALSE(Settings::MovePGXPDepthThresholdOutOfDepthBuffer(si));
  EXPECT_FALSE(si.ContainsValue("GPU", "PGXPDepthBuffer"));
  EXPECT_FALSE(si.ContainsValue("GPU", "PGXPDepthClearThreshold"));

  si.SetStringValue("GPU", "PGXPDepthBuffer", "true");
  EXPECT_FALSE(Settings::MovePGXPDepthThresholdOutOfDepthBuffer(si));
  EXPECT_TRUE(si.GetBoolValue("GPU", "PGXPDepthBuffer", false));
  EXPECT_FALSE(si.ContainsValue("GPU", "PGXPDepthClearThreshold"));
}
