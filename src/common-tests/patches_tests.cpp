// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Patches: the marks GooseStation keeps beside the bundled cheat database, which of a game's codes are patches, and
// what each needs.

#include "core/cheats.h"
#include "core/patches.h"

#include "core_host_stubs.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/path.h"
#include "common/string_util.h"

#include <gtest/gtest.h>

#include <algorithm>
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
