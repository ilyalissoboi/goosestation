// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include <span>
#include <string>
#include <string_view>
#include <vector>

/// Android's patch codes for a game: the file of the app's own that says which codes the player switched on or off,
/// and how the player's .cht files and the database's codes for every disc combine into one list. It holds no Android
/// headers and knows nothing of the core's CheatList, so it builds and is tested on every platform.
namespace AndroidPatchCodes {

/// A code the player switched on or off, by its group (empty for none) and name.
struct Switch
{
  std::string group;
  std::string name;
  bool on;
};

/// The switches in the app's file: one per `on|off<TAB>group<TAB>name` line. Blank lines, `#` comments and anything
/// else are skipped.
std::vector<Switch> Parse(std::string_view text);

/// The app's file for switches: a header comment, then one line each. A switch whose group or name holds a tab or a
/// line break can't be written, and is left out.
std::string Format(std::span<const Switch> switches);

/// name with each character an SD card's file system or Windows refuses (`< > : " / \ | ? *`, control characters)
/// replaced by `_`.
std::string SafeFileName(std::string_view name);

/// The app's file for the game keyed key, in the cheats folder: `<key>.patchcodes`.
std::string FileName(std::string_view key);

/// A code as a list has it: its group (empty for none) and name, whether its file marks it on (`[*Name]`), and
/// whether it's applied by hand rather than every frame.
struct Listed
{
  std::string group;
  std::string name;
  bool marked_on;
  bool manual;
};

/// One code of the combined list: from the player's codes or the database's, its index there, and whether it's on.
struct Entry
{
  bool from_player;
  size_t index;
  bool on;
};

/// The game's list: player (each player file's codes, in reading order), then database (every disc's), each code once
/// by group and name, the first one kept, so a player's file wins over the database. A code is on if the app's
/// switches say so, else if a player's file marks it on; the database's start off, and a manual code is never on.
std::vector<Entry> Combine(std::span<const Listed> player, std::span<const Listed> database,
                           std::span<const Switch> switches);

/// A code of the game's list as it stands: on by default (marked so in its file) or not, and on now or not.
struct State
{
  std::string group;
  std::string name;
  bool default_on;
  bool on;
};

/// The app's switches after a change: one for each of codes whose on differs from its default, after old's for codes
/// not in codes (a disc or file that isn't here today), so none is forgotten.
std::vector<Switch> Remember(std::span<const Switch> old, std::span<const State> codes);

} // namespace AndroidPatchCodes
