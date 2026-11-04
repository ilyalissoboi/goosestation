// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <optional>
#include <span>
#include <string>
#include <vector>

/// The memory card editor's side of the core: which cards there are, and every operation on one. It holds no state:
/// a card goes in and out as its bytes.
namespace MemcardEditorBridge {

/// A library game as its card is found: its title, and the serial and path it boots with.
struct GameCard
{
  std::string title;
  std::string serial;
  std::string boot_path;
  bool playlist = false;
};

/// MemcardKind's values, in its order.
enum class CardKind : u8
{
  Game,
  Shared,
  Other,
};

/// A card the editor lists: its file's full path, its name, its kind, and whether the file exists.
struct CardFile
{
  std::string path;
  std::string name;
  CardKind kind;
  bool exists;
};

/// Each game's own card in slots 1 and 2 (a per-game type only), at the path it boots with, named by the game (the
/// first game on a path names it), in the order given; then the shared cards that exist, that settings.ini makes the
/// type, or that a game uses; then the other card files: those settings name, and every .mcd in the memory card folder,
/// by name. On the CPU thread, waiting for it.
std::vector<CardFile> ListCards(std::span<const GameCard> games);

/// The most of a file ReadCard() reads: more than any card or save the core imports (a .gme card is under 132 KiB).
inline constexpr size_t READ_LIMIT = 256 * 1024;

/// A card file's bytes, the first READ_LIMIT of them, or nullopt without a file. On the CPU thread, waiting for it.
std::optional<std::vector<u8>> ReadCard(const std::string& path);

/// A save on a card, as EnumerateFiles() lists it, with its icon's first frame (16 x 16 RGBA8), or none.
struct Save
{
  u32 first_block;
  std::string file_name;
  std::string title;
  u32 blocks;
  bool deleted;
  std::vector<u32> icon;
};

/// What's on a card: its free blocks and its saves, deleted ones too.
struct Contents
{
  u32 free_blocks;
  std::vector<Save> saves;
};

/// The card's contents, or nullopt unless it's a card image (128 KiB, starting "MC").
std::optional<Contents> GetContents(std::span<const u8> card);

/// A formatted card.
std::vector<u8> Format();

/// An edit's new card, or nullopt with the reason.
struct Edit
{
  std::optional<std::vector<u8>> card;
  std::string error;
};

/// Copies the live save at from_block on from to to. With replace, a live save of the same file name on to goes first;
/// without, it's refused.
Edit CopySave(std::span<const u8> from, u32 from_block, std::span<const u8> to, bool replace);

/// Deletes the save at first_block (permanently: its blocks are cleared, and it can't be undeleted), or nullopt without
/// such a save.
std::optional<std::vector<u8>> DeleteSave(std::span<const u8> card, u32 first_block, bool permanently);

/// Undeletes the deleted save at first_block, or nullopt if it can't be (its blocks reused, or no such save).
std::optional<std::vector<u8>> UndeleteSave(std::span<const u8> card, u32 first_block);

/// What renaming the live save at first_block would meet: a new title (in the stored title's style) and file name,
/// each unchanged when nullopt. The errors are MemoryCardHelpers::TitleError's and FileNameError's values as numbers.
struct RenameCheck
{
  u32 title_error;
  u32 name_error;
  bool single_byte_title;
  bool title_not_shown_exactly;
  bool name_not_standard;
};
RenameCheck CheckRename(std::span<const u8> card, u32 first_block, const std::optional<std::string>& title,
                        const std::optional<std::string>& name);

/// Renames the live save at first_block, or nullopt if CheckRename() finds an error or there's no such save.
std::optional<std::vector<u8>> Rename(std::span<const u8> card, u32 first_block, const std::optional<std::string>& title,
                                      const std::optional<std::string>& name);

/// MemoryCardImage::ImportSave() of data, a file named file_name, onto card.
Edit ImportSave(std::span<const u8> card, const std::string& file_name, std::span<const u8> data);

/// MemoryCardImage::ImportCard() of data, a file named file_name, onto a formatted card.
Edit ImportCard(const std::string& file_name, std::span<const u8> data);

/// The live save at first_block as an .mcs file.
Edit ExportSave(std::span<const u8> card, u32 first_block);

/// Whether memory card sync knows the card at path (MemcardSync::IsCardKnown()).
bool IsKnownToSync(const std::string& path);

/// MemcardWriteOutcome's values, in its order.
enum class WriteOutcome : u8
{
  Saved,
  Changed,
  Busy,
  Failed,
};

struct WriteResult
{
  WriteOutcome outcome;
  std::string error;
};

/// Writes card to path, unless (without force) the file isn't expected any more (nullopt: no file): Changed. A card
/// sync knows is written under its hold (MemcardSyncBridge::HoldCard()), Busy if that's refused. On the CPU thread,
/// waiting for it.
WriteResult WriteCard(const std::string& path, std::span<const u8> card,
                      const std::optional<std::span<const u8>>& expected, bool force);

} // namespace MemcardEditorBridge
