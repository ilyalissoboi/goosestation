// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <array>
#include <optional>
#include <span>
#include <string_view>

// Memory card rules that need no emulator state, so they can be unit-tested: what a save may be renamed to, and
// which save a game's writes went to.

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

/// The save a game's writes went to. Bit n of `written_blocks` and of each of `save_block_masks` is block n; block 0
/// is the directory. Returns the index of the only save that owns every written data block, or nullopt when no data
/// block was written or the writes don't belong to exactly one save.
std::optional<size_t> FindWrittenSave(std::span<const u16> save_block_masks, u16 written_blocks);

} // namespace MemoryCardHelpers
