// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// The newer community cheat format (read from its public description only), and the extras our own .cht files keep
// for codes read from it. Every fixture here is written by hand for these tests.

#include "core/cheats.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/path.h"

#include <gtest/gtest.h>

#include <cstdlib>
#include <string>

namespace {

// The code's instructions as "XXXXXXXX YYYYYYYY" lines, to compare them as text.
std::string Lines(const CheatCode& code)
{
  std::string text;
  for (const CheatCode::Instruction& inst : code.instructions)
  {
    char line[32];
    std::snprintf(line, sizeof(line), "%08X %08X\n", inst.first, inst.second);
    text += line;
  }
  return text;
}

bool HasNoteContaining(const CheatCode& code, std::string_view text)
{
  for (const std::string& note : code.notes)
  {
    if (note.find(text) != std::string::npos)
      return true;
  }
  return false;
}

CheatList LoadINI(const std::string& text)
{
  CheatList list;
  EXPECT_TRUE(list.LoadFromINIString(text));
  return list;
}

} // namespace

TEST(CheatsFormat, INIReadsEntries)
{
  const CheatList list = LoadINI("; a comment\n"
                                 "# another\n"
                                 "[Movement\\Moon Jump]\n"
                                 "Type = Gameshark\n"
                                 "Activation = Manual\n"
                                 "Author = A\n"
                                 "Description = Hold X\n"
                                 "80001234 0001\n"
                                 "80001236 0002\n"
                                 "\n"
                                 "[Infinite Lives]\n"
                                 "80005678 0009\n");
  ASSERT_EQ(list.GetCodeCount(), 2u);

  const CheatCode& jump = list.GetCode(0);
  EXPECT_EQ(jump.group, "Movement");
  EXPECT_EQ(jump.description, "Moon Jump");
  EXPECT_TRUE(jump.IsManuallyActivated());
  EXPECT_EQ(jump.author, "A");
  EXPECT_EQ(jump.details, "Hold X");
  EXPECT_EQ(Lines(jump), "80001234 00000001\n80001236 00000002\n");
  EXPECT_FALSE(jump.patch);

  const CheatCode& lives = list.GetCode(1);
  EXPECT_EQ(lives.group, "Ungrouped");
  EXPECT_EQ(lives.description, "Infinite Lives");
  EXPECT_EQ(lives.activation, CheatCode::Activation::EndFrame);
  EXPECT_TRUE(lives.CanEnable());
}

TEST(CheatsFormat, INIOptionsReplaceNibbles)
{
  CheatList list = LoadINI("[Speed]\n"
                           "Option = Slow:1\n"
                           "Option = Fast:0x20\n"
                           "80001234 00??\n");
  ASSERT_EQ(list.GetCodeCount(), 1u);
  CheatCode& code = list.GetCode(0);
  ASSERT_TRUE(code.HasOptions());
  ASSERT_EQ(code.options.size(), 2u);
  EXPECT_EQ(code.options[0].name, "Slow");
  EXPECT_EQ(code.options[1].value, 0x20u);

  // Until one is chosen, the first option's value is in the code.
  EXPECT_EQ(code.option_value, 1u);
  EXPECT_EQ(Lines(code), "80001234 00000001\n");
  EXPECT_TRUE(code.CanEnable());

  EXPECT_TRUE(code.SetOptionValue(0x20, nullptr));
  EXPECT_EQ(Lines(code), "80001234 00000020\n");
}

TEST(CheatsFormat, INIShiftsValueToItsNibbles)
{
  CheatList list = LoadINI("[Level]\n"
                           "OptionRange = 0:255\n"
                           "80001234 ??00\n");
  CheatCode& code = list.GetCode(0);
  ASSERT_TRUE(code.option_range.has_value());
  EXPECT_EQ(code.option_range->first, 0u);
  EXPECT_EQ(code.option_range->second, 255u);
  EXPECT_EQ(Lines(code), "80001234 00000000\n");

  EXPECT_TRUE(code.SetOptionValue(0b101, nullptr));
  EXPECT_EQ(Lines(code), "80001234 00000500\n");
}

TEST(CheatsFormat, INIRejectsBadValues)
{
  CheatList list = LoadINI("[Range]\n"
                           "OptionRange = 0:255\n"
                           "80001234 00??\n"
                           "[Two Runs]\n"
                           "OptionRange = 0:3\n"
                           "80001234 ?0?0\n"
                           "[No Nibbles]\n"
                           "Option = One:1\n"
                           "80001234 0001\n"
                           "[Too Wide]\n"
                           "Option = Big:0x123\n"
                           "80001234 00??\n");
  ASSERT_EQ(list.GetCodeCount(), 4u);

  CheatCode& range = list.GetCode(0);
  Error error;
  EXPECT_FALSE(range.SetOptionValue(300, &error));
  EXPECT_FALSE(error.GetDescription().empty());
  EXPECT_EQ(Lines(range), "80001234 00000000\n"); // unchanged
  EXPECT_TRUE(range.CanEnable());

  for (u32 i = 1; i < 4; i++)
  {
    const CheatCode& bad = list.GetCode(i);
    EXPECT_FALSE(bad.CanEnable()) << bad.description;
    EXPECT_FALSE(bad.notes.empty()) << bad.description;
  }
}

TEST(CheatsFormat, INIOverridesMarkPatch)
{
  const CheatList list = LoadINI("[Widescreen 16:9]\n"
                                 "OverrideAspectRatio = 16:9\n"
                                 "OverrideCPUOverclock = 150\n"
                                 "DisableWidescreenRendering = true\n"
                                 "OverrideRAMSize = 8MB\n"
                                 "80001234 1555\n"
                                 "[Plain]\n"
                                 "80001234 0001\n");
  const CheatCode& patch = list.GetCode(0);
  EXPECT_TRUE(patch.patch);
  ASSERT_EQ(patch.overrides.size(), 4u);
  EXPECT_EQ(patch.overrides[0].first, "OverrideAspectRatio");
  EXPECT_EQ(patch.overrides[0].second, "16:9");
  EXPECT_EQ(patch.overrides[1].second, "150");
  EXPECT_EQ(patch.overrides[2].first, "DisableWidescreenRendering");
  EXPECT_EQ(patch.overrides[3].first, "OverrideRAMSize");
  EXPECT_TRUE(HasNoteContaining(patch, "OverrideRAMSize"));
  EXPECT_FALSE(HasNoteContaining(patch, "OverrideAspectRatio"));

  EXPECT_FALSE(list.GetCode(1).patch);
}

TEST(CheatsFormat, INIUnknownAndIgnore)
{
  const CheatList list = LoadINI("[Kept]\n"
                                 "Foo = Bar\n"
                                 "80001234 0001\n"
                                 "[Skipped]\n"
                                 "Ignore = true\n"
                                 "80001234 0002\n"
                                 "[Asm]\n"
                                 "Type = Assembly\n"
                                 "Activation = StartFrame\n"
                                 "80010000 lui $v0, 0x8001\n");
  ASSERT_EQ(list.GetCodeCount(), 2u);

  const CheatCode& kept = list.GetCode(0);
  EXPECT_EQ(kept.description, "Kept");
  ASSERT_EQ(kept.overrides.size(), 1u);
  EXPECT_EQ(kept.overrides[0].first, "Foo");
  EXPECT_FALSE(kept.patch);
  EXPECT_TRUE(HasNoteContaining(kept, "Foo"));
  EXPECT_TRUE(kept.CanEnable());

  const CheatCode& assembly = list.GetCode(1);
  EXPECT_EQ(assembly.type, CheatCode::Type::Assembly);
  EXPECT_FALSE(assembly.CanEnable());
  EXPECT_TRUE(HasNoteContaining(assembly, "Assembly"));
  // StartFrame is applied at the end of each frame, which is the start of the next.
  EXPECT_EQ(assembly.activation, CheatCode::Activation::EndFrame);
  EXPECT_TRUE(HasNoteContaining(assembly, "StartFrame"));
}

TEST(CheatsFormat, DetectsINI)
{
  EXPECT_EQ(CheatList::DetectFileFormat(std::string("; header\n[Name]\nType = Gameshark\n80001234 0001\n")),
            CheatList::Format::INI);
  EXPECT_EQ(CheatList::DetectFileFormat(std::string("[Name]\nOption = A:1\n80001234 00??\n")),
            CheatList::Format::INI);
  EXPECT_EQ(CheatList::DetectFileFormat(std::string("#group=Ungrouped\n#type=Gameshark\n[Name]\n80001234 0001\n")),
            CheatList::Format::PCSXR);
}

TEST(CheatsFormat, ChtRoundTripKeepsExtras)
{
  const CheatList source = LoadINI("[Video\\Widescreen]\n"
                                   "Author = A\n"
                                   "Description = Wider\n"
                                   "Option = 16:9:1\n"
                                   "Option = 21:9:2\n"
                                   "OverrideAspectRatio = 16:9\n"
                                   "Foo = Bar\n"
                                   "80001234 000?\n"
                                   "[Range]\n"
                                   "OptionRange = 1:9\n"
                                   "80001234 000?\n"
                                   "[Asm]\n"
                                   "Type = Assembly\n"
                                   "80010000 lui $v0, 0x8001\n");
  ASSERT_EQ(source.GetCodeCount(), 3u);

  const char* tmpdir = std::getenv("TMPDIR");
  const std::string path = Path::Combine((tmpdir && *tmpdir) ? tmpdir : "/tmp", "cheats-format-roundtrip.cht");
  CheatList writer = source;
  ASSERT_TRUE(writer.SaveToPCSXRFile(path.c_str()));
  const std::optional<std::string> text = FileSystem::ReadFileToString(path.c_str());
  FileSystem::DeleteFile(path.c_str());
  ASSERT_TRUE(text.has_value());

  CheatList read;
  ASSERT_TRUE(read.LoadFromPCSXRString(text.value()));
  ASSERT_EQ(read.GetCodeCount(), source.GetCodeCount());
  for (u32 i = 0; i < source.GetCodeCount(); i++)
  {
    const CheatCode& a = source.GetCode(i);
    const CheatCode& b = read.GetCode(i);
    EXPECT_EQ(a.group, b.group);
    EXPECT_EQ(a.description, b.description);
    EXPECT_EQ(a.type, b.type);
    EXPECT_EQ(a.author, b.author);
    EXPECT_EQ(a.details, b.details);
    ASSERT_EQ(a.options.size(), b.options.size());
    for (size_t j = 0; j < a.options.size(); j++)
    {
      EXPECT_EQ(a.options[j].name, b.options[j].name);
      EXPECT_EQ(a.options[j].value, b.options[j].value);
    }
    EXPECT_EQ(a.option_range, b.option_range);
    EXPECT_EQ(a.raw_lines, b.raw_lines);
    EXPECT_EQ(a.overrides, b.overrides);
    EXPECT_EQ(a.patch, b.patch);
    EXPECT_EQ(a.option_value, b.option_value);
    EXPECT_EQ(Lines(a), Lines(b));
  }
}

TEST(CheatsFormat, ApplySkipsPatchesAndAssembly)
{
  CheatList list = LoadINI("[Cheat]\n"
                           "80001234 0001\n"
                           "[Patch]\n"
                           "OverrideAspectRatio = 16:9\n"
                           "80001238 0001\n"
                           "[Asm]\n"
                           "Type = Assembly\n"
                           "80010000 nop\n");
  for (u32 i = 0; i < list.GetCodeCount(); i++)
    list.SetCodeEnabled(i, true);

  EXPECT_TRUE(CheatList::IsAppliedAsCheat(list.GetCode(0)));
  EXPECT_FALSE(CheatList::IsAppliedAsCheat(list.GetCode(1)));
  EXPECT_FALSE(CheatList::IsAppliedAsCheat(list.GetCode(2)));
  EXPECT_EQ(list.GetEnabledCodeCount(), 1u);
}

TEST(CheatsFormat, EditingACodeWithValuesKeepsItsTemplate)
{
  // Qt's cheat editor shows a code's instructions and reads them back: a code with values shows its `?` lines, and an
  // edit becomes the new template, kept by the .cht and by the next value chosen.
  CheatList list = LoadINI("[Speed]\nOption = Slow:1\nOption = Fast:0x20\n80001234 00??\n");
  CheatCode& code = list.GetCode(0);
  EXPECT_EQ(code.GetInstructionsAsString(), "80001234 00??\n");

  ASSERT_TRUE(code.SetInstructionsFromString("80005678 00??\n80005680 0001\n"));
  EXPECT_EQ(code.raw_lines, (std::vector<std::string>{"80005678 00??", "80005680 0001"}));
  EXPECT_EQ(Lines(code), "80005678 00000001\n80005680 00000001\n");
  ASSERT_TRUE(code.SetOptionValue(0x20, nullptr));
  EXPECT_EQ(Lines(code), "80005678 00000020\n80005680 00000001\n");

  // An edit the values can't go into is refused, the code as it was.
  EXPECT_FALSE(code.SetInstructionsFromString("80005678 0001\n"));
  EXPECT_EQ(Lines(code), "80005678 00000020\n80005680 00000001\n");

  // A plain code is edited as before.
  CheatList plain = LoadINI("[Plain]\n80001234 0001\n");
  ASSERT_TRUE(plain.GetCode(0).SetInstructionsFromString("80001238 0002\n"));
  EXPECT_TRUE(plain.GetCode(0).raw_lines.empty());
  EXPECT_EQ(Lines(plain.GetCode(0)), "80001238 00000002\n");
}
