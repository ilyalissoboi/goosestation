// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/android_patch_codes.h"

#include <gtest/gtest.h>

#include <string>
#include <vector>

using AndroidPatchCodes::Combine;
using AndroidPatchCodes::Entry;
using AndroidPatchCodes::FileName;
using AndroidPatchCodes::Format;
using AndroidPatchCodes::Listed;
using AndroidPatchCodes::Parse;
using AndroidPatchCodes::Remember;
using AndroidPatchCodes::SafeFileName;
using AndroidPatchCodes::State;
using AndroidPatchCodes::Switch;

namespace {

bool operator==(const Switch& a, const Switch& b)
{
  return a.group == b.group && a.name == b.name && a.on == b.on;
}

} // namespace

TEST(AndroidPatchCodes, ParseAndFormatRoundTrip)
{
  const std::vector<Switch> switches = {{"Player", "Infinite HP", true}, {"", "Max money", false}};

  const std::string text = Format(switches);

  EXPECT_EQ(text.rfind("# GooseStation: ", 0), 0u);
  EXPECT_NE(text.find("on\tPlayer\tInfinite HP\n"), std::string::npos);
  EXPECT_NE(text.find("off\t\tMax money\n"), std::string::npos);
  const std::vector<Switch> parsed = Parse(text);
  ASSERT_EQ(parsed.size(), 2u);
  EXPECT_TRUE(parsed[0] == switches[0]);
  EXPECT_TRUE(parsed[1] == switches[1]);
}

TEST(AndroidPatchCodes, ParseSkipsCommentsAndJunk)
{
  const std::vector<Switch> parsed =
    Parse("# a comment\n\nmaybe\tPlayer\tX\non\tPlayer\nyes\nOn\tPlayer\tY\r\noff\tGroup\tZ:Note\n");

  ASSERT_EQ(parsed.size(), 1u);
  EXPECT_EQ(parsed[0].group, "Group");
  EXPECT_EQ(parsed[0].name, "Z:Note");
  EXPECT_FALSE(parsed[0].on);
}

TEST(AndroidPatchCodes, ParseTakesWindowsLineEnds)
{
  const std::vector<Switch> parsed = Parse("on\tPlayer\tInfinite HP\r\n");

  ASSERT_EQ(parsed.size(), 1u);
  EXPECT_EQ(parsed[0].name, "Infinite HP");
}

TEST(AndroidPatchCodes, FormatLeavesOutTabs)
{
  const std::vector<Switch> switches = {{"A\tB", "Name", true}, {"Group", "Two\nlines", true}, {"G", "Ok", true}};

  const std::vector<Switch> parsed = Parse(Format(switches));

  ASSERT_EQ(parsed.size(), 1u);
  EXPECT_EQ(parsed[0].name, "Ok");
}

TEST(AndroidPatchCodes, SafeFileNameReplacesWhatFileSystemsRefuse)
{
  EXPECT_EQ(SafeFileName("Ace Combat 3: Electrosphere"), "Ace Combat 3_ Electrosphere");
  EXPECT_EQ(SafeFileName("a<b>c\"d/e\\f|g?h*i"), "a_b_c_d_e_f_g_h_i");
  EXPECT_EQ(SafeFileName("Final Fantasy VII"), "Final Fantasy VII");
}

TEST(AndroidPatchCodes, FileNameIsTheKey)
{
  EXPECT_EQ(FileName("SLPS-02020"), "SLPS-02020.patchcodes");
}

TEST(AndroidPatchCodes, CombinePlayerWinsOverDatabase)
{
  const std::vector<Listed> player = {{"Player", "Infinite HP", false, false}};
  const std::vector<Listed> database = {{"Player", "Infinite HP", false, false}, {"Player", "Max MP", false, false}};

  const std::vector<Entry> entries = Combine(player, database, {});

  ASSERT_EQ(entries.size(), 2u);
  EXPECT_TRUE(entries[0].from_player);
  EXPECT_EQ(entries[0].index, 0u);
  EXPECT_FALSE(entries[1].from_player);
  EXPECT_EQ(entries[1].index, 1u);
}

TEST(AndroidPatchCodes, CombineDedupesAcrossDiscs)
{
  // Two discs' blocks of the database, the same codes in each.
  const std::vector<Listed> database = {{"", "Infinite Missiles", false, false},
                                        {"", "Max Money", false, false},
                                        {"", "Infinite Missiles", false, false},
                                        {"", "Max Money", false, false}};

  const std::vector<Entry> entries = Combine({}, database, {});

  ASSERT_EQ(entries.size(), 2u);
  EXPECT_EQ(entries[0].index, 0u);
  EXPECT_EQ(entries[1].index, 1u);
}

TEST(AndroidPatchCodes, CombineKeepsPerDiscVariants)
{
  // Grand Theft Auto's discs give "Infinite Lives" different addresses; Max Money is the same on both.
  const std::vector<Listed> database = {{"", "Infinite Lives", false, false, "800A 01", 1},
                                        {"", "Max Money", false, false, "800B 02", 1},
                                        {"", "Infinite Lives", false, false, "800C 03", 2},
                                        {"", "Max Money", false, false, "800B 02", 2}};

  const std::vector<Entry> entries = Combine({}, database, {});

  ASSERT_EQ(entries.size(), 3u);
  EXPECT_EQ(entries[0].name, "Infinite Lives (Disc 1)");
  EXPECT_EQ(entries[1].name, "Max Money");
  EXPECT_EQ(entries[2].name, "Infinite Lives (Disc 2)");
  EXPECT_EQ(entries[2].index, 2u);
}

TEST(AndroidPatchCodes, CombinePlayerCodeReplacesEveryVariant)
{
  const std::vector<Listed> player = {{"", "Infinite Lives", false, false, "800D 04", 0}};
  const std::vector<Listed> database = {{"", "Infinite Lives", false, false, "800A 01", 1},
                                        {"", "Infinite Lives", false, false, "800C 03", 2}};

  const std::vector<Entry> entries = Combine(player, database, {});

  ASSERT_EQ(entries.size(), 1u);
  EXPECT_TRUE(entries[0].from_player);
  EXPECT_EQ(entries[0].name, "Infinite Lives");
}

TEST(AndroidPatchCodes, CombineSwitchesAVariantByItsName)
{
  const std::vector<Listed> database = {{"", "Infinite Lives", false, false, "800A 01", 1},
                                        {"", "Infinite Lives", false, false, "800C 03", 2}};
  const std::vector<Switch> switches = {{"", "Infinite Lives (Disc 2)", true}};

  const std::vector<Entry> entries = Combine({}, database, switches);

  EXPECT_FALSE(entries[0].on);
  EXPECT_TRUE(entries[1].on);
}

TEST(AndroidPatchCodes, CombineDatabaseStartsOff)
{
  const std::vector<Listed> database = {{"", "Infinite Missiles", true, false}};

  EXPECT_FALSE(Combine({}, database, {})[0].on);
}

TEST(AndroidPatchCodes, CombinePlayerMarkStartsOn)
{
  const std::vector<Listed> player = {{"", "Infinite Missiles", true, false}, {"", "Max Money", false, false}};

  const std::vector<Entry> entries = Combine(player, {}, {});

  EXPECT_TRUE(entries[0].on);
  EXPECT_FALSE(entries[1].on);
}

TEST(AndroidPatchCodes, CombineHonoursTheAppsFile)
{
  const std::vector<Listed> player = {{"", "Marked", true, false}};
  const std::vector<Listed> database = {{"Group", "Database", false, false}};
  const std::vector<Switch> switches = {{"", "Marked", false}, {"Group", "Database", true}};

  const std::vector<Entry> entries = Combine(player, database, switches);

  ASSERT_EQ(entries.size(), 2u);
  EXPECT_FALSE(entries[0].on);
  EXPECT_TRUE(entries[1].on);
}

TEST(AndroidPatchCodes, CombineManualIsNeverOn)
{
  const std::vector<Listed> player = {{"", "Max Money Now", true, true}};
  const std::vector<Switch> switches = {{"", "Max Money Now", true}};

  EXPECT_FALSE(Combine(player, {}, switches)[0].on);
}

TEST(AndroidPatchCodes, RememberKeepsSwitchesForAbsentCodes)
{
  // Disc 2's file isn't here today: its switch stays.
  const std::vector<Switch> old = {{"", "Disc 2 code", true}, {"", "Here", true}};
  const std::vector<State> codes = {{"", "Here", false, false}};

  const std::vector<Switch> remembered = Remember(old, codes);

  ASSERT_EQ(remembered.size(), 1u);
  EXPECT_EQ(remembered[0].name, "Disc 2 code");
}

TEST(AndroidPatchCodes, RememberWritesOnlyDifferences)
{
  const std::vector<State> codes = {{"", "Off as it was", false, false},
                                    {"", "Switched on", false, true},
                                    {"", "Switched off", true, false},
                                    {"", "On as marked", true, true}};

  const std::vector<Switch> remembered = Remember({}, codes);

  ASSERT_EQ(remembered.size(), 2u);
  EXPECT_TRUE(remembered[0] == (Switch{"", "Switched on", true}));
  EXPECT_TRUE(remembered[1] == (Switch{"", "Switched off", false}));
}
