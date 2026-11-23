// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/settings.h"

#include "common/memory_settings_interface.h"

#include <gtest/gtest.h>

#include <cstring>

namespace {
DisplayVSyncMode Loaded(MemorySettingsInterface& si)
{
  Settings settings;
  settings.display_vsync_mode = DisplayVSyncMode::Off;
  settings.Load(si, si);
  return settings.display_vsync_mode;
}
} // namespace

TEST(SettingsVSync, AutomaticByDefault)
{
  MemorySettingsInterface si;
  EXPECT_EQ(Loaded(si), DisplayVSyncMode::Automatic);
}

TEST(SettingsVSync, AnOldTrueReadsOn)
{
  MemorySettingsInterface si;
  si.SetBoolValue("Display", "VSync", true);
  EXPECT_EQ(Loaded(si), DisplayVSyncMode::On);
}

TEST(SettingsVSync, AnOldFalseReadsAutomatic)
{
  // False was the default every file was written with: it says nothing about the player's choice.
  MemorySettingsInterface si;
  si.SetBoolValue("Display", "VSync", false);
  EXPECT_EQ(Loaded(si), DisplayVSyncMode::Automatic);
}

TEST(SettingsVSync, TheModeWinsOverTheOldKey)
{
  MemorySettingsInterface si;
  si.SetBoolValue("Display", "VSync", true);
  si.SetStringValue("Display", "VSyncMode", "Off");
  EXPECT_EQ(Loaded(si), DisplayVSyncMode::Off);
}

TEST(SettingsVSync, SaveWritesTheModeOnly)
{
  Settings settings;
  settings.display_vsync_mode = DisplayVSyncMode::On;
  MemorySettingsInterface saved;
  settings.Save(saved, false);
  EXPECT_EQ(saved.GetStringValue("Display", "VSyncMode", ""), "On");
  EXPECT_FALSE(saved.ContainsValue("Display", "VSync"));
  EXPECT_EQ(Loaded(saved), DisplayVSyncMode::On);
}

TEST(SettingsVSync, NamesRoundTrip)
{
  for (u32 i = 0; i < static_cast<u32>(DisplayVSyncMode::Count); i++)
  {
    const DisplayVSyncMode mode = static_cast<DisplayVSyncMode>(i);
    EXPECT_EQ(Settings::ParseDisplayVSyncMode(Settings::GetDisplayVSyncModeName(mode)), mode);
    EXPECT_NE(std::strlen(Settings::GetDisplayVSyncModeDisplayName(mode)), 0u);
  }
  EXPECT_STREQ(Settings::GetDisplayVSyncModeName(DisplayVSyncMode::Automatic), "Automatic");
  EXPECT_STREQ(Settings::GetDisplayVSyncModeName(DisplayVSyncMode::On), "On");
  EXPECT_STREQ(Settings::GetDisplayVSyncModeName(DisplayVSyncMode::Off), "Off");
  EXPECT_FALSE(Settings::ParseDisplayVSyncMode("Sometimes").has_value());
}

TEST(SettingsVSync, SyncToHostIsOnByDefault)
{
  MemorySettingsInterface si;
  Settings settings;
  settings.sync_to_host_refresh_rate = false;
  settings.Load(si, si);
  EXPECT_TRUE(settings.sync_to_host_refresh_rate);
}

TEST(SettingsVSync, DesktopDefaultsTurnSyncOnOnce)
{
  MemorySettingsInterface si;
  si.SetBoolValue("Main", "SyncToHostRefreshRate", false);
  EXPECT_TRUE(Settings::ApplyDesktopPacingDefaults(si));
  EXPECT_TRUE(si.GetBoolValue("Main", "SyncToHostRefreshRate", false));
  EXPECT_EQ(si.GetUIntValue("Main", "DesktopPacingDefaultsVersion", 0u), 1u);

  // The player turns it off again: it stays off.
  si.SetBoolValue("Main", "SyncToHostRefreshRate", false);
  EXPECT_FALSE(Settings::ApplyDesktopPacingDefaults(si));
  EXPECT_FALSE(si.GetBoolValue("Main", "SyncToHostRefreshRate", true));
}
