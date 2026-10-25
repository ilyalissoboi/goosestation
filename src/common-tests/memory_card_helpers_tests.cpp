// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/memory_card_helpers.h"
#include <gtest/gtest.h>

#include <algorithm>
#include <cstring>
#include <string>

using MemoryCardHelpers::FileNameError;
using MemoryCardHelpers::TitleError;

static FileNameError Check(std::string_view name, std::span<const std::string_view> other_names = {})
{
  return MemoryCardHelpers::CheckFileName(name, other_names);
}

TEST(MemoryCardHelpers, CheckFileName)
{
  ASSERT_EQ(Check("BASLUS-00067DRACULA"), FileNameError::None);
  ASSERT_EQ(Check(std::string(20, 'A')), FileNameError::None);
  ASSERT_EQ(Check("my save"), FileNameError::None);
  ASSERT_EQ(Check(""), FileNameError::Empty);
  ASSERT_EQ(Check(std::string(21, 'A')), FileNameError::TooLong);
  ASSERT_EQ(Check("BASLUS-00067\x01"), FileNameError::InvalidCharacter);
  ASSERT_EQ(Check("BASLUS*"), FileNameError::InvalidCharacter);
  ASSERT_EQ(Check("BAD?"), FileNameError::InvalidCharacter);
  ASSERT_EQ(Check("\xC3\xA9"), FileNameError::InvalidCharacter);

  const std::string_view others[] = {"BASLUS-00067"};
  ASSERT_EQ(Check("BASLUS-00067", others), FileNameError::Duplicate);
  ASSERT_EQ(Check("BASLUS-00068", others), FileNameError::None);
}

TEST(MemoryCardHelpers, IsStandardFileName)
{
  ASSERT_TRUE(MemoryCardHelpers::IsStandardFileName("BASLUS-00067DRACULA"));
  ASSERT_TRUE(MemoryCardHelpers::IsStandardFileName("BESCES-00344"));
  ASSERT_TRUE(MemoryCardHelpers::IsStandardFileName("BISLPS-00700"));
  ASSERT_TRUE(MemoryCardHelpers::IsStandardFileName("BISLPM_86034"));
  ASSERT_FALSE(MemoryCardHelpers::IsStandardFileName("BXSLUS-00067"));
  ASSERT_FALSE(MemoryCardHelpers::IsStandardFileName("SLUS-00067"));
  ASSERT_FALSE(MemoryCardHelpers::IsStandardFileName("BASLUS-0006"));
  ASSERT_FALSE(MemoryCardHelpers::IsStandardFileName("my save"));
}

TEST(MemoryCardHelpers, EncodeTitle)
{
  std::array<u8, MemoryCardHelpers::TITLE_SIZE> title;

  ASSERT_EQ(MemoryCardHelpers::EncodeTitle("CRASH", &title), TitleError::None);
  const u8 crash[] = {0x82, 0x62, 0x82, 0x71, 0x82, 0x60, 0x82, 0x72, 0x82, 0x67};
  ASSERT_TRUE(std::equal(std::begin(crash), std::end(crash), title.begin()));
  ASSERT_TRUE(std::all_of(title.begin() + sizeof(crash), title.end(), [](u8 b) { return b == 0; }));

  ASSERT_EQ(MemoryCardHelpers::EncodeTitle(std::string(32, 'A'), &title), TitleError::None);
  ASSERT_EQ(MemoryCardHelpers::EncodeTitle(std::string(33, 'A'), &title), TitleError::TooLong);
  ASSERT_EQ(MemoryCardHelpers::EncodeTitle("\xF0\x9F\x98\x80", &title), TitleError::Unencodable);

  ASSERT_EQ(MemoryCardHelpers::EncodeTitle("", &title), TitleError::None);
  ASSERT_TRUE(std::all_of(title.begin(), title.end(), [](u8 b) { return b == 0; }));
}

TEST(MemoryCardHelpers, EncodeTitleLeavesOutputOnError)
{
  std::array<u8, MemoryCardHelpers::TITLE_SIZE> title;
  title.fill(0xAB);
  ASSERT_EQ(MemoryCardHelpers::EncodeTitle(std::string(33, 'A'), &title), TitleError::TooLong);
  ASSERT_TRUE(std::all_of(title.begin(), title.end(), [](u8 b) { return b == 0xAB; }));
}

TEST(MemoryCardHelpers, FindWrittenSave)
{
  // Save 0 owns blocks 1 and 2, save 1 owns block 3; block 0 is the directory.
  const u16 masks[] = {0b0110, 0b1000};
  ASSERT_EQ(MemoryCardHelpers::FindWrittenSave(masks, 0b0111), 0u);
  ASSERT_EQ(MemoryCardHelpers::FindWrittenSave(masks, 0b0010), 0u);
  ASSERT_EQ(MemoryCardHelpers::FindWrittenSave(masks, 0b1001), 1u);
  ASSERT_EQ(MemoryCardHelpers::FindWrittenSave(masks, 0b0001), std::nullopt);
  ASSERT_EQ(MemoryCardHelpers::FindWrittenSave(masks, 0b1010), std::nullopt);
  ASSERT_EQ(MemoryCardHelpers::FindWrittenSave(masks, 0b10000), std::nullopt);
  ASSERT_EQ(MemoryCardHelpers::FindWrittenSave(masks, 0), std::nullopt);
}

static std::array<u8, MemoryCardHelpers::TITLE_SIZE> StoredTitle(std::string_view sjis)
{
  std::array<u8, MemoryCardHelpers::TITLE_SIZE> title = {};
  std::memcpy(title.data(), sjis.data(), sjis.size());
  return title;
}

TEST(MemoryCardHelpers, IsTitleShownExactly)
{
  std::array<u8, MemoryCardHelpers::TITLE_SIZE> title;
  ASSERT_EQ(MemoryCardHelpers::EncodeTitle("Mega-Man X's \"1\"", &title), TitleError::None);
  ASSERT_TRUE(MemoryCardHelpers::IsTitleShownExactly(title));
  ASSERT_TRUE(MemoryCardHelpers::IsTitleShownExactly(StoredTitle("\x83\x5A\x83\x75"))); // セブ

  // Single-byte titles keep their style, so they come back the same.
  ASSERT_TRUE(MemoryCardHelpers::IsTitleShownExactly(StoredTitle("CRASH BANDICOOT 2")));

  // The editor shows ー as '-' and ・ as a space.
  ASSERT_FALSE(MemoryCardHelpers::IsTitleShownExactly(StoredTitle("\x83\x5A\x81\x5B\x83\x75"))); // セーブ
  ASSERT_FALSE(MemoryCardHelpers::IsTitleShownExactly(StoredTitle("\x83\x5A\x81\x45\x83\x75"))); // セ・ブ
}

TEST(MemoryCardHelpers, GetTitleStyle)
{
  using MemoryCardHelpers::TitleStyle;
  ASSERT_EQ(MemoryCardHelpers::GetTitleStyle(StoredTitle("CRASH")), TitleStyle::SingleByte);
  ASSERT_EQ(MemoryCardHelpers::GetTitleStyle(StoredTitle("\x82\x62\x82\x71")), TitleStyle::FullWidth); // ＣＲ
  ASSERT_EQ(MemoryCardHelpers::GetTitleStyle(StoredTitle("A\x82\x60")), TitleStyle::FullWidth);
  ASSERT_EQ(MemoryCardHelpers::GetTitleStyle(StoredTitle("")), TitleStyle::FullWidth);
}

TEST(MemoryCardHelpers, EncodeTitleSingleByte)
{
  using MemoryCardHelpers::TitleStyle;
  std::array<u8, MemoryCardHelpers::TITLE_SIZE> title;

  ASSERT_EQ(MemoryCardHelpers::EncodeTitle("Mega-Man X's \"1\"", &title, TitleStyle::SingleByte), TitleError::None);
  ASSERT_EQ(title, StoredTitle("Mega-Man X's \"1\""));

  ASSERT_EQ(MemoryCardHelpers::EncodeTitle(std::string(64, 'A'), &title, TitleStyle::SingleByte), TitleError::None);
  ASSERT_EQ(MemoryCardHelpers::EncodeTitle(std::string(65, 'A'), &title, TitleStyle::SingleByte), TitleError::TooLong);

  // Anything beyond printable ASCII makes the whole title full-width: mixing the two would misread.
  ASSERT_EQ(MemoryCardHelpers::EncodeTitle("A\xE3\x82\xBB", &title, TitleStyle::SingleByte), TitleError::None); // Aセ
  ASSERT_EQ(title, StoredTitle("\x82\x60\x83\x5A"));
}
