// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/android_memcards.h"
#include <gtest/gtest.h>

using AndroidMemcards::OtherCardName;
using AndroidMemcards::PlaylistCardTitle;

TEST(AndroidMemcards, PlaylistCardTitle)
{
  ASSERT_EQ(PlaylistCardTitle("FF7 (USA)", "Final Fantasy VII", false, true), "FF7 (USA)");
  ASSERT_EQ(PlaylistCardTitle("FF7 (USA)", "Final Fantasy VII", true, true), std::nullopt);
  ASSERT_EQ(PlaylistCardTitle("FF7 (USA)", "Final Fantasy VII", false, false), std::nullopt);
  ASSERT_EQ(PlaylistCardTitle("Final Fantasy VII", "Final Fantasy VII", false, true), std::nullopt);
  ASSERT_EQ(PlaylistCardTitle("FF7 (USA)", "", false, true), std::nullopt);
}

TEST(AndroidMemcards, OtherCardName)
{
  ASSERT_EQ(OtherCardName("Crash_1.mcd"), "Crash");
  ASSERT_EQ(OtherCardName("Backup.mcd"), "Backup");
  ASSERT_EQ(OtherCardName("Crash_2.mcd"), "Crash_2");
  ASSERT_EQ(OtherCardName("odd.MCD"), "odd");
}
