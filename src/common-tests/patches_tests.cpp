// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Patches: the marks GooseStation keeps beside the bundled cheat database, which of a game's codes are patches, and
// what each needs.

#include "core/cheats.h"
#include "core/patches.h"
#include "core/settings.h"
#include "core/system.h"

#include "util/ini_settings_interface.h"

#include "core_host_stubs.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/memory_settings_interface.h"
#include "common/path.h"
#include "common/string_util.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cstdlib>
#include <map>
#include <set>
#include <string>
#include <vector>

namespace {

// The one code of a .cht text.
CheatCode CodeFrom(const std::string& cht)
{
  CheatList list;
  EXPECT_TRUE(list.LoadFromPCSXRString(cht));
  EXPECT_EQ(list.GetCodeCount(), 1u);
  return list.GetCodeCount() ? list.GetCode(0) : CheatCode();
}

CheatCode NamedCode(std::string group, std::string name)
{
  CheatCode code = CodeFrom("[x]\n80001234 0001\n");
  code.group = std::move(group);
  code.description = std::move(name);
  return code;
}

} // namespace

TEST(PatchesMarks, Parse)
{
  Error error;
  const Patches::Marks marks = Patches::Marks::Parse("; GooseStation's patch marks\n"
                                                     "\n"
                                                     "SLUS-01224\tWidescreen 16-10\twidescreen 16:10\n"
                                                     "SLUS-00255\tUncap Game at 60 FPS\tframerate overclock=160 "
                                                     "choice=Frame rate\n"
                                                     "SLUS-00001\tPlay at 60 FPS\tframerate\n"
                                                     "SCPS-10047\tSelect Widescreen Type\\Widescreen Vert-\t"
                                                     "widescreen 16:9 choice=Select Widescreen Type\n"
                                                     "SLES-00001\tPAL2NTSC\tdisplaymode\n"
                                                     "SLUS-00002\tBroken\tsparkles\n",
                                                     &error);
  EXPECT_EQ(marks.Count(), 5u);
  EXPECT_NE(error.GetDescription().find("line 8"), std::string::npos) << error.GetDescription();

  const Patches::Mark* wide = marks.Find("SLUS-01224", "Ungrouped", "Widescreen 16-10");
  ASSERT_NE(wide, nullptr);
  EXPECT_EQ(wide->kind, Patches::Kind::Widescreen);
  EXPECT_EQ(wide->needs.aspect_numerator, 16u);
  EXPECT_EQ(wide->needs.aspect_denominator, 10u);
  EXPECT_TRUE(wide->needs.disable_widescreen_rendering);
  EXPECT_EQ(wide->needs.overclock_percent, 0u);
  EXPECT_TRUE(wide->choice.empty());

  const Patches::Mark* uncap = marks.Find("SLUS-00255", "", "Uncap Game at 60 FPS");
  ASSERT_NE(uncap, nullptr);
  EXPECT_EQ(uncap->kind, Patches::Kind::FrameRate);
  EXPECT_EQ(uncap->needs.overclock_percent, 160u);
  EXPECT_FALSE(uncap->needs.may_need_overclock);
  EXPECT_FALSE(uncap->needs.disable_widescreen_rendering);
  EXPECT_EQ(uncap->choice, "Frame rate");

  const Patches::Mark* plain = marks.Find("SLUS-00001", "Ungrouped", "Play at 60 FPS");
  ASSERT_NE(plain, nullptr);
  EXPECT_TRUE(plain->needs.may_need_overclock);

  const Patches::Mark* vert = marks.Find("SCPS-10047", "Select Widescreen Type", "Widescreen Vert-");
  ASSERT_NE(vert, nullptr);
  EXPECT_EQ(vert->needs.aspect_numerator, 16u);
  EXPECT_EQ(vert->needs.aspect_denominator, 9u);
  EXPECT_EQ(vert->choice, "Select Widescreen Type");

  const Patches::Mark* ntsc = marks.Find("SLES-00001", "Ungrouped", "PAL2NTSC");
  ASSERT_NE(ntsc, nullptr);
  EXPECT_EQ(ntsc->kind, Patches::Kind::DisplayMode);
}

TEST(PatchesMarks, WidescreenDefaultsTo16By9)
{
  const Patches::Marks marks = Patches::Marks::Parse("SLUS-01040\tWidescreen 16-9\twidescreen\n", nullptr);
  const Patches::Mark* mark = marks.Find("SLUS-01040", "Ungrouped", "Widescreen 16-9");
  ASSERT_NE(mark, nullptr);
  EXPECT_EQ(mark->needs.aspect_numerator, 16u);
  EXPECT_EQ(mark->needs.aspect_denominator, 9u);
}

TEST(PatchesMarks, FindBySerialAndName)
{
  const Patches::Marks marks = Patches::Marks::Parse("SLUS-01040\tWidescreen 16-9\twidescreen 16:9\n", nullptr);
  EXPECT_NE(marks.Find("SLUS-01040", "Ungrouped", "Widescreen 16-9"), nullptr);
  EXPECT_EQ(marks.Find("SLUS-01041", "Ungrouped", "Widescreen 16-9"), nullptr);
  EXPECT_EQ(marks.Find("SLUS-01040", "Ungrouped", "Widescreen 16-9 (Alt)"), nullptr);
  EXPECT_EQ(marks.Find("SLUS-01040", "Video", "Widescreen 16-9"), nullptr);
}

TEST(PatchesClassify, ByMark)
{
  const Patches::Marks marks = Patches::Marks::Parse("SLUS-01040\tWidescreen 16-9\twidescreen 16:9\n", nullptr);
  CheatCode code = NamedCode("Ungrouped", "Widescreen 16-9");
  const std::optional<Patches::Mark> mark = Patches::Classify("SLUS-01040", code, marks);
  ASSERT_TRUE(mark.has_value());
  EXPECT_EQ(mark->kind, Patches::Kind::Widescreen);
  EXPECT_TRUE(code.patch);

  CheatCode cheat = NamedCode("Ungrouped", "Infinite HP");
  EXPECT_FALSE(Patches::Classify("SLUS-01040", cheat, marks).has_value());
  EXPECT_FALSE(cheat.patch);
}

TEST(PatchesClassify, ByOverrides)
{
  CheatList list;
  ASSERT_TRUE(list.LoadFromINIString("[Wide]\nOverrideAspectRatio = 16:9\nDisableWidescreenRendering = true\n"
                                     "80001234 0001\n"
                                     "[Fast]\nOverrideCPUOverclock = 200\n80001234 0002\n"
                                     "[NTSC]\nOverrideVRAMSize = 1MB\n80001234 0003\n"));
  const Patches::Marks none;

  CheatCode wide = list.GetCode(0);
  const std::optional<Patches::Mark> wide_mark = Patches::Classify("SLUS-00001", wide, none);
  ASSERT_TRUE(wide_mark.has_value());
  EXPECT_EQ(wide_mark->kind, Patches::Kind::Widescreen);
  EXPECT_EQ(wide_mark->needs.aspect_numerator, 16u);
  EXPECT_EQ(wide_mark->needs.aspect_denominator, 9u);
  EXPECT_TRUE(wide_mark->needs.disable_widescreen_rendering);

  CheatCode fast = list.GetCode(1);
  const std::optional<Patches::Mark> fast_mark = Patches::Classify("SLUS-00001", fast, none);
  ASSERT_TRUE(fast_mark.has_value());
  EXPECT_EQ(fast_mark->kind, Patches::Kind::FrameRate);
  EXPECT_EQ(fast_mark->needs.overclock_percent, 200u);

  CheatCode other = list.GetCode(2);
  const std::optional<Patches::Mark> other_mark = Patches::Classify("SLUS-00001", other, none);
  ASSERT_TRUE(other_mark.has_value());
  EXPECT_EQ(other_mark->kind, Patches::Kind::Other);
}

TEST(PatchesClassify, ByPatchLine)
{
  CheatCode code = CodeFrom("#patch\n[NTSC Mode]\n80001234 0001\n");
  const std::optional<Patches::Mark> mark = Patches::Classify("SLES-00001", code, Patches::Marks());
  ASSERT_TRUE(mark.has_value());
  EXPECT_EQ(mark->kind, Patches::Kind::Other);
}

TEST(PatchesRestore, OnlyWithA7)
{
  // King's Field II's 60 fps uncap: restoring writes only.
  EXPECT_TRUE(CodeFrom("[Uncap]\nA7019656 14402400\nA7079272 10401000\n").RestoresOnDisable());
  // Crash Warped's widescreen: plain writes.
  EXPECT_FALSE(CodeFrom("[Widescreen 16-9]\n80017A4C 0C00\n80017A4E 2402\n").RestoresOnDisable());
  // A restoring write and a plain one.
  EXPECT_FALSE(CodeFrom("[Mixed]\nA7019656 14402400\n30012345 0001\n").RestoresOnDisable());
  // A condition, then a restoring write.
  EXPECT_TRUE(CodeFrom("[Conditional]\nD0012345 0001\nA7019656 14402400\n").RestoresOnDisable());
  // Conditions only: nothing to restore, nothing written either.
  EXPECT_FALSE(CodeFrom("[Nothing]\nD0012345 0001\n").RestoresOnDisable());
}

// The shipped marks, chtdb_patches.txt, against the shipped database, chtdb.txt.
namespace {

class PatchesBundledMarks : public testing::Test
{
protected:
  void SetUp() override
  {
    if (!FileSystem::DirectoryExists(GOOSESTATION_TEST_RESOURCES_DIR))
      GTEST_SKIP() << "No source tree at " << GOOSESTATION_TEST_RESOURCES_DIR;
    CoreHostStubs::SetResourceDirectory(GOOSESTATION_TEST_RESOURCES_DIR);

    const std::optional<std::string> text =
      FileSystem::ReadFileToString(Path::Combine(GOOSESTATION_TEST_RESOURCES_DIR, "chtdb_patches.txt").c_str());
    ASSERT_TRUE(text.has_value()) << "chtdb_patches.txt is missing";
    m_text = text.value();
    Error error;
    m_marks = Patches::Marks::Parse(m_text, &error);
    EXPECT_FALSE(error.IsValid()) << error.GetDescription();
  }

  // Each line's serial and code key.
  std::vector<std::pair<std::string, std::string>> Lines() const
  {
    std::vector<std::pair<std::string, std::string>> lines;
    for (const std::string_view line : StringUtil::SplitString(m_text, '\n'))
    {
      if (line.empty() || line.front() == ';')
        continue;
      const std::vector<std::string_view> fields = StringUtil::SplitString(line, '\t');
      EXPECT_EQ(fields.size(), 3u) << line;
      if (fields.size() == 3)
        lines.emplace_back(std::string(fields[0]), std::string(fields[1]));
    }
    return lines;
  }

  // The marks of serial's codes, as the core reads them from the database.
  std::vector<Patches::Mark> MarksFor(const std::string& serial) const
  {
    CheatList list;
    list.LoadFromPackage(serial);
    std::vector<Patches::Mark> marks;
    for (u32 i = 0; i < list.GetCodeCount(); i++)
    {
      if (const Patches::Mark* mark = m_marks.Find(serial, list.GetCode(i).group, list.GetCode(i).description))
        marks.push_back(*mark);
    }
    return marks;
  }

  static size_t CountKind(const std::vector<Patches::Mark>& marks, Patches::Kind kind)
  {
    return static_cast<size_t>(
      std::count_if(marks.begin(), marks.end(), [kind](const Patches::Mark& m) { return m.kind == kind; }));
  }

  std::string m_text;
  Patches::Marks m_marks;
};

} // namespace

TEST_F(PatchesBundledMarks, NameRealCodes)
{
  // Every line is a code the core reads for its serial.
  std::map<std::string, std::set<std::string>> keys_by_serial;
  const std::vector<std::pair<std::string, std::string>> lines = Lines();
  EXPECT_GT(lines.size(), 500u);
  EXPECT_EQ(m_marks.Count(), lines.size());
  for (const auto& [serial, key] : lines)
  {
    auto it = keys_by_serial.find(serial);
    if (it == keys_by_serial.end())
    {
      CheatList list;
      list.LoadFromPackage(serial);
      std::set<std::string> keys;
      for (u32 i = 0; i < list.GetCodeCount(); i++)
        keys.insert(Patches::CodeKey(list.GetCode(i).group, list.GetCode(i).description));
      it = keys_by_serial.emplace(serial, std::move(keys)).first;
    }
    EXPECT_TRUE(it->second.contains(key)) << serial << " has no code '" << key << "'";
  }
}

TEST_F(PatchesBundledMarks, CoverLibrary)
{
  // The user's library: a widescreen patch for each, 16:10 for Arc the Lad, Vert- for Alundra 2.
  for (const char* serial : {"SLUS-01040", "SCUS-94491", "SLUS-00422", "SLUS-01324", "SCUS-94900", "SCUS-94154",
                             "SCPS-10047", "SCUS-94244", "SLUS-01158", "SLUS-00866", "SLUS-01017", "SLUS-01224"})
  {
    EXPECT_GE(CountKind(MarksFor(serial), Patches::Kind::Widescreen), 1u) << serial;
  }

  const std::vector<Patches::Mark> arc = MarksFor("SLUS-01224");
  ASSERT_EQ(arc.size(), 1u);
  EXPECT_EQ(arc[0].needs.aspect_numerator, 16u);
  EXPECT_EQ(arc[0].needs.aspect_denominator, 10u);

  // Crash 2 (Japan)'s Hor+ and Vert- are one choice.
  const std::vector<Patches::Mark> crash2jp = MarksFor("SCPS-10047");
  ASSERT_EQ(crash2jp.size(), 2u);
  EXPECT_FALSE(crash2jp[0].choice.empty());
  EXPECT_EQ(crash2jp[0].choice, crash2jp[1].choice);

  // Frame rate: Crash Warped (two variants, one choice), Parasite Eve II, King's Field II (30 and 60, one choice).
  for (const char* serial : {"SCUS-94244", "SLUS-01042", "SLUS-00255"})
    EXPECT_GE(CountKind(MarksFor(serial), Patches::Kind::FrameRate), 1u) << serial;

  std::vector<Patches::Mark> kf2 = MarksFor("SLUS-00255");
  std::erase_if(kf2, [](const Patches::Mark& m) { return m.kind != Patches::Kind::FrameRate; });
  ASSERT_EQ(kf2.size(), 2u);
  EXPECT_FALSE(kf2[0].choice.empty());
  EXPECT_EQ(kf2[0].choice, kf2[1].choice);
  EXPECT_EQ(kf2[0].needs.overclock_percent, 200u); // the 30 fps uncap's name says 200
  EXPECT_EQ(kf2[1].needs.overclock_percent, 160u); // the 60 fps uncap's, 160
}

TEST_F(PatchesBundledMarks, SkipCheats)
{
  for (const auto& [serial, key] : Lines())
  {
    for (const char* cheat : {"Harold's 2 Codes", "Trophy Room", "Uncapped Fireball", "Slowmotion", "Turbo mode",
                              "Uncap weapon", "Remove Dither"})
    {
      EXPECT_EQ(key.find(cheat), std::string::npos) << serial << " " << key;
    }
  }
}

TEST_F(PatchesBundledMarks, SelectCheatGroupsUntouched)
{
  // Only Select groups of patches are marked; the thousands of Select groups of cheats (levels, items) aren't.
  for (const auto& [serial, key] : Lines())
  {
    if (!key.starts_with("Select "))
      continue;
    const std::string group = key.substr(0, key.rfind('\\'));
    EXPECT_TRUE(group.find("Widescreen") != std::string::npos || group.find("FPS") != std::string::npos)
      << serial << " " << key;
  }
}

// The per-game list of patches.
namespace {

Patches::Mark WideMark(std::string choice = {})
{
  Patches::Mark mark;
  mark.kind = Patches::Kind::Widescreen;
  mark.needs.aspect_numerator = 16;
  mark.needs.aspect_denominator = 9;
  mark.needs.disable_widescreen_rendering = true;
  mark.choice = std::move(choice);
  return mark;
}

Patches::Mark RateMark(u32 overclock, std::string choice = {})
{
  Patches::Mark mark;
  mark.kind = Patches::Kind::FrameRate;
  mark.needs.overclock_percent = overclock;
  mark.choice = std::move(choice);
  return mark;
}

// A plain-write code (doesn't restore) and a restoring one (A7), by group and name.
CheatCode PlainCode(std::string group, std::string name, bool marked_on = false)
{
  CheatCode code = CodeFrom("[x]\n80017A4C 0C00\n");
  code.group = std::move(group);
  code.description = std::move(name);
  code.enabled = marked_on;
  return code;
}

CheatCode RestoringCode(std::string group, std::string name)
{
  CheatCode code = CodeFrom("[x]\nA7019656 14402400\n");
  code.group = std::move(group);
  code.description = std::move(name);
  return code;
}

// Crash 2 (Japan)'s choice, and a plain patch.
Patches::PatchList Crash2Japan()
{
  std::vector<std::pair<CheatCode, Patches::Mark>> codes;
  codes.emplace_back(PlainCode("Select Widescreen Type", "Widescreen Hor+"), WideMark("Select Widescreen Type"));
  codes.emplace_back(PlainCode("Select Widescreen Type", "Widescreen Vert-"), WideMark("Select Widescreen Type"));
  codes.emplace_back(RestoringCode("Ungrouped", "60 FPS"), RateMark(150));
  return Patches::PatchList::Build(std::move(codes));
}

} // namespace

TEST(PatchesList, BuildGroupsChoices)
{
  const Patches::PatchList list = Crash2Japan();
  ASSERT_EQ(list.Count(), 2u);
  const Patches::Patch& wide = list.Get(0);
  EXPECT_EQ(wide.title, "Select Widescreen Type");
  EXPECT_EQ(wide.kind, Patches::Kind::Widescreen);
  ASSERT_EQ(wide.choices.size(), 2u);
  EXPECT_EQ(wide.choices[0].code.description, "Widescreen Hor+");
  EXPECT_EQ(wide.choices[1].code.description, "Widescreen Vert-");
  EXPECT_EQ(wide.saved, -1);
  EXPECT_EQ(wide.running, -1);

  const Patches::Patch& rate = list.Get(1);
  EXPECT_EQ(rate.title, "60 FPS");
  EXPECT_TRUE(rate.group.empty());
  ASSERT_EQ(rate.choices.size(), 1u);
}

TEST(PatchesList, SwitchingOnIsImmediate)
{
  Patches::PatchList list = Crash2Japan();
  EXPECT_FALSE(list.NeedsRestart(0, 0));
  EXPECT_EQ(list.Set(0, 0), nullptr);
  EXPECT_EQ(list.Get(0).saved, 0);
  EXPECT_EQ(list.Get(0).running, 0);
}

TEST(PatchesList, OnlyOneChoiceOn)
{
  Patches::PatchList list = Crash2Japan();
  list.Set(0, 0);
  // Hor+ is plain writes: changing to Vert- waits for a restart.
  EXPECT_TRUE(list.NeedsRestart(0, 1));
  list.Set(0, 1);
  EXPECT_EQ(list.Get(0).saved, 1);
  EXPECT_EQ(list.Get(0).running, 0);
  list.Restart();
  EXPECT_EQ(list.Get(0).running, 1);

  // Never both: the switches name one choice on.
  size_t on = 0;
  for (const Patches::Switch& s : list.Switches())
    on += s.on ? 1 : 0;
  EXPECT_EQ(on, 1u);
}

TEST(PatchesList, RestoringPatchSwitchesOffAtOnce)
{
  Patches::PatchList list = Crash2Japan();
  list.Set(1, 0);
  EXPECT_FALSE(list.NeedsRestart(1, -1));
  const CheatCode* undo = list.Set(1, -1);
  ASSERT_NE(undo, nullptr);
  EXPECT_EQ(undo->description, "60 FPS");
  EXPECT_EQ(list.Get(1).saved, -1);
  EXPECT_EQ(list.Get(1).running, -1);
}

TEST(PatchesList, PlainPatchWaits)
{
  Patches::PatchList list = Crash2Japan();
  list.Set(0, 1);
  EXPECT_TRUE(list.NeedsRestart(0, -1));
  EXPECT_EQ(list.Set(0, -1), nullptr);
  EXPECT_EQ(list.Get(0).saved, -1);
  EXPECT_EQ(list.Get(0).running, 1); // still running, with its needs, until the next start
  EXPECT_EQ(list.RunningNeeds().aspect_numerator, 16u);
  list.Restart();
  EXPECT_EQ(list.Get(0).running, -1);
  EXPECT_EQ(list.RunningNeeds().aspect_numerator, 0u);
}

TEST(PatchesList, SwitchesOnlyDifferences)
{
  std::vector<std::pair<CheatCode, Patches::Mark>> codes;
  codes.emplace_back(PlainCode("Ungrouped", "Widescreen 16-9", true), WideMark());
  codes.emplace_back(RestoringCode("Ungrouped", "60 FPS"), RateMark(0));
  Patches::PatchList list = Patches::PatchList::Build(std::move(codes));
  EXPECT_TRUE(list.Switches().empty());

  list.Set(0, -1);
  const std::vector<Patches::Switch> off = list.Switches();
  ASSERT_EQ(off.size(), 1u);
  EXPECT_EQ(off[0].code, "Widescreen 16-9");
  EXPECT_FALSE(off[0].on);

  list.Set(0, 0);
  EXPECT_TRUE(list.Switches().empty());
}

TEST(PatchesList, RestoreKeepsOldOnState)
{
  // A widescreen code the player's .cht marks on ([*Widescreen 16-9]) stays on as a patch, without any switch.
  std::vector<std::pair<CheatCode, Patches::Mark>> codes;
  codes.emplace_back(PlainCode("Ungrouped", "Widescreen 16-9", true), WideMark());
  Patches::PatchList list = Patches::PatchList::Build(std::move(codes));
  list.Restore({});
  EXPECT_EQ(list.Get(0).saved, 0);
  EXPECT_EQ(list.Get(0).running, 0);

  // Switches win over the marker.
  const std::vector<Patches::Switch> switches = {{"Widescreen 16-9", false, ""}};
  list.Restore(switches);
  EXPECT_EQ(list.Get(0).saved, -1);
  EXPECT_EQ(list.Get(0).running, -1);
}

TEST(PatchesList, RestoreChoosesFirstOn)
{
  Patches::PatchList list = Crash2Japan();
  const std::vector<Patches::Switch> switches = {{"Select Widescreen Type\\Widescreen Vert-", true, ""},
                                                 {"Select Widescreen Type\\Widescreen Hor+", true, ""},
                                                 {"60 FPS", true, ""},
                                                 {"Not a patch", true, ""}};
  list.Restore(switches);
  EXPECT_EQ(list.Get(0).saved, 1);
  EXPECT_EQ(list.Get(0).running, 1);
  EXPECT_EQ(list.Get(1).running, 0);
}

TEST(PatchesList, RunningNeedsCombine)
{
  std::vector<std::pair<CheatCode, Patches::Mark>> codes;
  Patches::Mark ultra = WideMark();
  ultra.needs.aspect_numerator = 21;
  codes.emplace_back(PlainCode("Ungrouped", "Widescreen 16-9"), WideMark());
  codes.emplace_back(PlainCode("Ungrouped", "21:9 Widescreen (Menus)"), ultra);
  codes.emplace_back(RestoringCode("Ungrouped", "60 FPS"), RateMark(160));
  codes.emplace_back(RestoringCode("Ungrouped", "Another uncap"), RateMark(200));
  Patches::PatchList list = Patches::PatchList::Build(std::move(codes));

  for (size_t i = 0; i < list.Count(); i++)
    list.Set(i, 0);
  Patches::Needs needs = list.RunningNeeds();
  EXPECT_EQ(needs.aspect_numerator, 16u); // the first patch with a ratio
  EXPECT_EQ(needs.aspect_denominator, 9u);
  EXPECT_TRUE(needs.disable_widescreen_rendering);
  EXPECT_EQ(needs.overclock_percent, 200u); // the larger

  // Switching one frame-rate patch off leaves the other's.
  list.Set(3, -1);
  needs = list.RunningNeeds();
  EXPECT_EQ(needs.overclock_percent, 160u);
  EXPECT_EQ(needs.aspect_numerator, 16u);
}

TEST(PatchesList, ValueRestoredByName)
{
  CheatList file;
  ASSERT_TRUE(file.LoadFromINIString("[Frame Rate]\nOverrideCPUOverclock = 150\nOption = 30 FPS:1\nOption = 60 FPS:2\n"
                                     "80001234 000?\n"
                                     "[Level]\nOverrideAspectRatio = 16:9\nOptionRange = 1:9\n80001238 000?\n"));
  std::vector<std::pair<CheatCode, Patches::Mark>> codes;
  for (u32 i = 0; i < file.GetCodeCount(); i++)
  {
    CheatCode code = file.GetCode(i);
    const std::optional<Patches::Mark> mark = Patches::Classify("SLUS-00001", code, Patches::Marks());
    ASSERT_TRUE(mark.has_value());
    codes.emplace_back(std::move(code), mark.value());
  }
  Patches::PatchList list = Patches::PatchList::Build(std::move(codes));

  EXPECT_TRUE(list.SetValue(0, 0, 2, nullptr));
  EXPECT_FALSE(list.SetValue(1, 0, 10, nullptr));
  EXPECT_TRUE(list.SetValue(1, 0, 7, nullptr));
  const std::vector<Patches::Switch> switches = list.Switches();
  ASSERT_EQ(switches.size(), 2u);
  EXPECT_EQ(switches[0].value, "60 FPS"); // by the option's name
  EXPECT_EQ(switches[1].value, "7");      // a number

  // A fresh list from the same codes takes the values back.
  std::vector<std::pair<CheatCode, Patches::Mark>> again;
  for (u32 i = 0; i < file.GetCodeCount(); i++)
  {
    CheatCode code = file.GetCode(i);
    again.emplace_back(code, Patches::Classify("SLUS-00001", code, Patches::Marks()).value());
  }
  Patches::PatchList restored = Patches::PatchList::Build(std::move(again));
  restored.Restore(switches);
  EXPECT_EQ(restored.Get(0).choices[0].code.option_value, 2u);
  EXPECT_EQ(restored.Get(1).choices[0].code.option_value, 7u);
  EXPECT_TRUE(restored.Switches().size() == 2u);
}

// Overrides: what running patches set for the game, in its settings only.
namespace {

Patches::PatchList WideAndFast(u32 overclock)
{
  std::vector<std::pair<CheatCode, Patches::Mark>> codes;
  codes.emplace_back(PlainCode("Ungrouped", "Widescreen 16-9"), WideMark());
  codes.emplace_back(RestoringCode("Ungrouped", "60 FPS"), RateMark(overclock));
  return Patches::PatchList::Build(std::move(codes));
}

Settings PlayerSettings()
{
  Settings s;
  s.display_aspect_ratio = DisplayAspectRatio::Auto;
  s.gpu_widescreen_hack = true;
  s.cpu_overclock_enable = false;
  s.cpu_overclock_numerator = 1;
  s.cpu_overclock_denominator = 1;
  s.UpdateOverclockActive();
  return s;
}

} // namespace

TEST(PatchesOverrides, Widescreen)
{
  Patches::PatchList list = WideAndFast(0);
  list.Set(0, 0);
  Settings s = PlayerSettings();
  list.ApplyOverrides(s);
  EXPECT_EQ(s.display_aspect_ratio, DisplayAspectRatio::R16_9);
  EXPECT_FALSE(s.gpu_widescreen_hack);
  EXPECT_FALSE(s.cpu_overclock_active);

  // Off (at the next start): the next load leaves the player's settings as they are.
  list.Set(0, -1);
  list.Restart();
  Settings again = PlayerSettings();
  list.ApplyOverrides(again);
  EXPECT_EQ(again.display_aspect_ratio, DisplayAspectRatio::Auto);
  EXPECT_TRUE(again.gpu_widescreen_hack);
}

TEST(PatchesOverrides, SixteenByTen)
{
  std::vector<std::pair<CheatCode, Patches::Mark>> codes;
  Patches::Mark mark = WideMark();
  mark.needs.aspect_denominator = 10;
  codes.emplace_back(PlainCode("Ungrouped", "Widescreen 16-10"), mark);
  Patches::PatchList list = Patches::PatchList::Build(std::move(codes));
  list.Set(0, 0);
  Settings s = PlayerSettings();
  list.ApplyOverrides(s);
  EXPECT_EQ(s.display_aspect_ratio, DisplayAspectRatio::Custom);
  EXPECT_EQ(s.display_aspect_ratio_custom_numerator, 16u);
  EXPECT_EQ(s.display_aspect_ratio_custom_denominator, 10u);
}

TEST(PatchesOverrides, Overclock)
{
  Patches::PatchList list = WideAndFast(160);
  list.Set(1, 0);
  Settings s = PlayerSettings();
  list.ApplyOverrides(s);
  EXPECT_TRUE(s.cpu_overclock_enable);
  EXPECT_TRUE(s.cpu_overclock_active);
  EXPECT_EQ(s.GetCPUOverclockPercent(), 160u);
  EXPECT_EQ(s.display_aspect_ratio, DisplayAspectRatio::Auto); // no widescreen patch on
}

TEST(PatchesOverrides, OverclockNeverLowers)
{
  Patches::PatchList list = WideAndFast(160);
  list.Set(1, 0);
  Settings s = PlayerSettings();
  s.cpu_overclock_enable = true;
  s.SetCPUOverclockPercent(200);
  s.UpdateOverclockActive();
  list.ApplyOverrides(s);
  EXPECT_EQ(s.GetCPUOverclockPercent(), 200u);
  EXPECT_TRUE(s.cpu_overclock_active);
}

TEST(PatchesOverrides, HandChangeWins)
{
  Patches::PatchList list = WideAndFast(160);
  list.Set(0, 0);
  list.Set(1, 0);
  Settings first = PlayerSettings();
  list.ApplyOverrides(first);
  ASSERT_EQ(first.display_aspect_ratio, DisplayAspectRatio::R16_9);

  // The player sets 4:3 by hand: the next load finds it, and the patch leaves it be, until the patch goes.
  Settings changed = PlayerSettings();
  changed.display_aspect_ratio = DisplayAspectRatio::R4_3;
  list.ApplyOverrides(changed);
  EXPECT_EQ(changed.display_aspect_ratio, DisplayAspectRatio::R4_3);
  EXPECT_FALSE(changed.gpu_widescreen_hack); // the widescreen setting wasn't touched by hand: still overridden
  EXPECT_EQ(changed.GetCPUOverclockPercent(), 160u);

  Settings later = PlayerSettings();
  later.display_aspect_ratio = DisplayAspectRatio::R4_3;
  list.ApplyOverrides(later);
  EXPECT_EQ(later.display_aspect_ratio, DisplayAspectRatio::R4_3);

  // Off and on again: it sets its ratio again.
  list.Set(0, -1);
  list.Restart();
  Settings off = PlayerSettings();
  list.ApplyOverrides(off);
  list.Set(0, 0);
  Settings on = PlayerSettings();
  list.ApplyOverrides(on);
  EXPECT_EQ(on.display_aspect_ratio, DisplayAspectRatio::R16_9);
}

TEST(PatchesOverrides, NeverWrite)
{
  MemorySettingsInterface si;
  si.SetStringValue("Display", "AspectRatio", "Auto (Game Native)");
  si.SetBoolValue("GPU", "WidescreenHack", true);
  const auto before = si.GetKeyValueList("Display");

  Settings s;
  s.Load(si, si);
  Patches::PatchList list = WideAndFast(160);
  list.Set(0, 0);
  list.Set(1, 0);
  list.ApplyOverrides(s);

  EXPECT_EQ(si.GetKeyValueList("Display"), before);
  EXPECT_TRUE(si.GetBoolValue("GPU", "WidescreenHack", false));
  EXPECT_FALSE(si.ContainsValue("CPU", "OverclockEnable"));
  EXPECT_TRUE(si.GetKeyValueList("Patches").empty());
}

TEST(PatchesOverrides, NotAllowedInHardcoreOrWithoutEnhancements)
{
  Settings s;
  EXPECT_TRUE(Patches::Allowed(s, false));
  EXPECT_FALSE(Patches::Allowed(s, true));
  s.disable_all_enhancements = true;
  EXPECT_FALSE(Patches::Allowed(s, false));
}

TEST(PatchesSwitches, RoundTripAwkwardNames)
{
  const std::vector<Patches::Switch> switches = {
    {"Widescreen 16-9", true, ""},
    {"Select Widescreen Type\\Widescreen 16-9 (Vert-)", false, ""},
    {"A = B; C # D", true, ""},
    {" Leading and trailing ", true, ""},
    {"\"Quoted\"", false, ""},
    {"ワイド画面 16:9", true, "60 FPS"},
    {"Level", false, "7"},
  };
  MemorySettingsInterface si;
  si.SetStringValue("Display", "AspectRatio", "Auto (Game Native)");
  Patches::WriteSwitches(si, switches);
  const std::vector<Patches::Switch> read = Patches::ReadSwitches(si);
  ASSERT_EQ(read.size(), switches.size());
  for (const Patches::Switch& expected : switches)
  {
    const auto it = std::find_if(read.begin(), read.end(), [&](const Patches::Switch& s) { return s.code == expected.code; });
    ASSERT_NE(it, read.end()) << expected.code;
    EXPECT_EQ(it->on, expected.on) << expected.code;
    EXPECT_EQ(it->value, expected.value) << expected.code;
  }

  // None left: the section goes, the rest stays.
  Patches::WriteSwitches(si, {});
  EXPECT_TRUE(si.GetKeyValueList("Patches").empty());
  EXPECT_EQ(si.GetStringValue("Display", "AspectRatio"), "Auto (Game Native)");
}

TEST(PatchesSwitches, SaveForSerialByteIdentical)
{
  const char* tmpdir = std::getenv("TMPDIR");
  std::string templ = Path::Combine((tmpdir && *tmpdir) ? tmpdir : "/tmp", "common-tests-patches-XXXXXX");
  ASSERT_NE(mkdtemp(templ.data()), nullptr);
  const std::string old_folder = EmuFolders::GameSettings;
  EmuFolders::GameSettings = templ;

  const std::vector<Patches::Switch> on = {{"Widescreen 16-9", true, ""}};
  const std::string path = System::GetGameSettingsPath("SLUS-99999");

  // No game settings before: none after on then off.
  ASSERT_TRUE(Patches::SaveSwitchesForSerial("SLUS-99999", on, nullptr));
  EXPECT_TRUE(FileSystem::FileExists(path.c_str()));
  ASSERT_TRUE(Patches::SaveSwitchesForSerial("SLUS-99999", {}, nullptr));
  EXPECT_FALSE(FileSystem::FileExists(path.c_str()));

  // Game settings the app wrote: the same bytes after on then off.
  {
    INISettingsInterface ini(path);
    ini.SetBoolValue("GPU", "PGXPEnable", true);
    ini.SetStringValue("Display", "AspectRatio", "4:3");
    ASSERT_TRUE(ini.Save());
  }
  const std::optional<std::string> before = FileSystem::ReadFileToString(path.c_str());
  ASSERT_TRUE(before.has_value());
  ASSERT_TRUE(Patches::SaveSwitchesForSerial("SLUS-99999", on, nullptr));
  const std::optional<std::string> during = FileSystem::ReadFileToString(path.c_str());
  ASSERT_TRUE(during.has_value());
  EXPECT_NE(during.value(), before.value());
  ASSERT_TRUE(Patches::SaveSwitchesForSerial("SLUS-99999", {}, nullptr));
  EXPECT_EQ(FileSystem::ReadFileToString(path.c_str()), before);

  EmuFolders::GameSettings = old_folder;
  FileSystem::RecursiveDeleteDirectory(templ.c_str());
}
