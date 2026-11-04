// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

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

} // namespace MemcardEditorBridge
