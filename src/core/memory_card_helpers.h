// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <array>
#include <span>
#include <string_view>

// Memory card rules that need no emulator state, so they can be unit-tested: what a save may be renamed to.

namespace MemoryCardHelpers {

inline constexpr u32 FILE_NAME_MAX_LENGTH = 20;
inline constexpr u32 TITLE_SIZE = 64;

enum class FileNameError
{
  None,
  Empty,
  TooLong,
  InvalidCharacter,
  Duplicate,
};

/// Whether `name` can be written as a save's file name, next to the card's other saves `other_names`.
FileNameError CheckFileName(std::string_view name, std::span<const std::string_view> other_names);

/// Whether `name` follows the usual pattern: B, a region letter (A, E or I), then a product code such as SLUS-00067.
bool IsStandardFileName(std::string_view name);

enum class TitleError
{
  None,
  Unencodable,
  TooLong,
};

/// How a title's ASCII is written: full-width, as most games do, or as single bytes, as some games do.
enum class TitleStyle
{
  FullWidth,
  SingleByte,
};

/// The style of a stored title: single-byte when every byte is ASCII, otherwise (or when empty) full-width.
TitleStyle GetTitleStyle(std::span<const u8, TITLE_SIZE> stored);

/// Encodes a title as the save header stores it: Shift-JIS, zero-padded. In the single-byte style, a title of only
/// printable ASCII is written as is; anything else is written full-width throughout, since the decoder misreads text
/// that mixes the two. `*out` is left as it was on error.
TitleError EncodeTitle(std::string_view utf8, std::array<u8, TITLE_SIZE>* out,
                       TitleStyle style = TitleStyle::FullWidth);

/// Whether the editor shows a stored title exactly, so that encoding what it shows, in the title's own style, gives
/// the same bytes back. The decoder simplifies some characters: ー shows as '-', ・ as a space.
bool IsTitleShownExactly(std::span<const u8, TITLE_SIZE> stored);

} // namespace MemoryCardHelpers
