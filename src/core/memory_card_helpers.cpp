// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "memory_card_helpers.h"

#include "util/shiftjis.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>

MemoryCardHelpers::FileNameError MemoryCardHelpers::CheckFileName(std::string_view name,
                                                                  std::span<const std::string_view> other_names)
{
  if (name.empty())
    return FileNameError::Empty;
  if (name.size() > FILE_NAME_MAX_LENGTH)
    return FileNameError::TooLong;

  // * and ? are wildcards to the BIOS's file functions.
  if (!std::all_of(name.begin(), name.end(), [](char c) { return c >= 0x20 && c <= 0x7E && c != '*' && c != '?'; }))
    return FileNameError::InvalidCharacter;

  if (std::find(other_names.begin(), other_names.end(), name) != other_names.end())
    return FileNameError::Duplicate;

  return FileNameError::None;
}

bool MemoryCardHelpers::IsStandardFileName(std::string_view name)
{
  static constexpr auto is_upper = [](char c) { return c >= 'A' && c <= 'Z'; };
  static constexpr auto is_digit = [](char c) { return c >= '0' && c <= '9'; };

  if (name.size() < 12 || name[0] != 'B' || (name[1] != 'A' && name[1] != 'E' && name[1] != 'I'))
    return false;

  const std::string_view code = name.substr(2, 10);
  return std::all_of(code.begin(), code.begin() + 4, is_upper) && (code[4] == '-' || code[4] == '_') &&
         std::all_of(code.begin() + 5, code.end(), is_digit);
}

MemoryCardHelpers::TitleStyle MemoryCardHelpers::GetTitleStyle(std::span<const u8, TITLE_SIZE> stored)
{
  const auto end = std::find(stored.begin(), stored.end(), 0);
  if (end == stored.begin() || !std::all_of(stored.begin(), end, [](u8 b) { return b < 0x80; }))
    return TitleStyle::FullWidth;
  return TitleStyle::SingleByte;
}

MemoryCardHelpers::TitleError MemoryCardHelpers::EncodeTitle(std::string_view utf8, std::array<u8, TITLE_SIZE>* out,
                                                             TitleStyle style)
{
  if (style == TitleStyle::SingleByte &&
      std::all_of(utf8.begin(), utf8.end(), [](char c) { return c >= 0x20 && c <= 0x7E; }))
  {
    if (utf8.size() > TITLE_SIZE)
      return TitleError::TooLong;

    out->fill(0);
    std::memcpy(out->data(), utf8.data(), utf8.size());
    return TitleError::None;
  }

  const std::optional<std::string> sjis = utf82sjis(utf8);
  if (!sjis.has_value())
    return TitleError::Unencodable;
  if (sjis->size() > TITLE_SIZE)
    return TitleError::TooLong;

  out->fill(0);
  std::memcpy(out->data(), sjis->data(), sjis->size());
  return TitleError::None;
}

bool MemoryCardHelpers::IsTitleShownExactly(std::span<const u8, TITLE_SIZE> stored)
{
  // Decoded as MemoryCardImage::EnumerateFiles does: sjis2utf8 reads two bytes at a time and edits its input.
  char sjis[TITLE_SIZE + 2] = {};
  std::memcpy(sjis, stored.data(), TITLE_SIZE);
  char* utf8 = sjis2utf8(sjis);
  std::array<u8, TITLE_SIZE> encoded;
  const bool exact = (EncodeTitle(utf8, &encoded, GetTitleStyle(stored)) == TitleError::None &&
                      std::equal(encoded.begin(), encoded.end(), stored.begin()));
  std::free(utf8);
  return exact;
}
