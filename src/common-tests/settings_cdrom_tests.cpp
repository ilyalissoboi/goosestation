// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/settings.h"

#include "common/memory_settings_interface.h"

#include <gtest/gtest.h>

TEST(CDROMSettings, NewDefaults)
{
  const Settings settings;
  EXPECT_EQ(settings.cdrom_read_speedup, 0u); // Maximum
  EXPECT_EQ(settings.cdrom_seek_speedup, 0u); // instant
  EXPECT_TRUE(settings.cdrom_short_drive_waits); // passed the compatibility pass
  EXPECT_FALSE(settings.fast_forward_loading);

  MemorySettingsInterface empty;
  Settings loaded;
  loaded.Load(empty, empty);
  EXPECT_EQ(loaded.cdrom_read_speedup, 0u);
  EXPECT_EQ(loaded.cdrom_seek_speedup, 0u);
  EXPECT_TRUE(loaded.cdrom_short_drive_waits);
}

TEST(CDROMSettings, LoadingDefaultsMoveOnce)
{
  MemorySettingsInterface si;
  si.SetIntValue("CDROM", "ReadSpeedup", 1);
  si.SetIntValue("CDROM", "SeekSpeedup", 1);

  EXPECT_TRUE(Settings::ApplyLoadingDefaults(si, "Main"));
  EXPECT_EQ(si.GetIntValue("CDROM", "ReadSpeedup", -1), 0);
  EXPECT_EQ(si.GetIntValue("CDROM", "SeekSpeedup", -1), 0);
  EXPECT_EQ(si.GetUIntValue("Main", "LoadingDefaultsVersion", 0u), 1u);

  // The player sets the old speeds back: they stay.
  si.SetIntValue("CDROM", "ReadSpeedup", 1);
  si.SetIntValue("CDROM", "SeekSpeedup", 1);
  EXPECT_FALSE(Settings::ApplyLoadingDefaults(si, "Main"));
  EXPECT_EQ(si.GetIntValue("CDROM", "ReadSpeedup", -1), 1);
  EXPECT_EQ(si.GetIntValue("CDROM", "SeekSpeedup", -1), 1);
}

TEST(CDROMSettings, KeepsChosenSpeeds)
{
  MemorySettingsInterface si;
  si.SetIntValue("CDROM", "ReadSpeedup", 4);
  si.SetIntValue("CDROM", "SeekSpeedup", 2);

  EXPECT_TRUE(Settings::ApplyLoadingDefaults(si, "Android"));
  EXPECT_EQ(si.GetIntValue("CDROM", "ReadSpeedup", -1), 4);
  EXPECT_EQ(si.GetIntValue("CDROM", "SeekSpeedup", -1), 2);
  EXPECT_EQ(si.GetUIntValue("Android", "LoadingDefaultsVersion", 0u), 1u);
}

TEST(CDROMSettings, FreshFileOnlyGetsTheVersion)
{
  MemorySettingsInterface si;
  EXPECT_TRUE(Settings::ApplyLoadingDefaults(si, "Main"));
  EXPECT_FALSE(si.ContainsValue("CDROM", "ReadSpeedup"));
  EXPECT_FALSE(si.ContainsValue("CDROM", "SeekSpeedup"));
}
