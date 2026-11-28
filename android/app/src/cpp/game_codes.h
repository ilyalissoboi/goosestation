// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "util/android_disc_groups.h"

#include "common/types.h"

#include <optional>
#include <string>
#include <utility>
#include <vector>

/// The running game's codes: one list for the whole game, from the player's .cht files and the database's codes for
/// every disc, split into patches (widescreen, frame-rate and display-mode codes, by the core's marks) and cheats. The
/// core is given both again after every disc change, which is a next start for patches waiting for one. Which codes
/// are on, and their values, are kept in the app's own file, cheats/<key>.patchcodes; the player's files are only read.
/// Everything here runs on the CPU thread.
namespace GameCodes {

/// A code's values, if it has any: its options (names and values), or a range, and the value in it now.
struct Values
{
  std::vector<std::pair<std::string, u32>> options;
  std::optional<std::pair<u32, u32>> range;
  u32 value = 0;
};

/// A cheat as the app shows it: its group (empty for none), name, whether it's applied by hand, whether it's on, and
/// its values.
struct Code
{
  std::string group;
  std::string name;
  bool manual;
  bool on;
  Values values;
};

/// A patch as the app shows it: its group (empty for none), title, its choices' names (one for a plain patch), the
/// choice its switches say and the one running now (-1: off; they differ until the next start), the values of the
/// choice shown (saved, or the first while it's off), a note (the overclock it runs with, or may need, and what its
/// file's code can't do), and whether it can be switched on.
struct Patch
{
  std::string group;
  std::string title;
  std::vector<std::string> choices;
  s32 saved;
  s32 running;
  Values values;
  std::string note;
  bool can_enable;
};

/// Builds game's list, before it boots.
void Load(const AndroidDiscGroups::Game& game);

/// Gives the core the game's cheats and patches, as each UpdateRunningGame() drops the ones it had
/// (Host::OnGameChanged()): the patches run their saved choices.
void Apply();

/// The game's cheats, in the list's order.
std::vector<Code> List();

/// The game's patches, in the list's order, as the running game has them.
std::vector<Patch> ListPatches();

/// The player's file the panel names for adding codes: `<game title>.cht`, in a form file systems take.
std::string AddFileName();

/// Switches cheat index on or off, at once, and keeps that in the app's file: nullopt, or why not. When the file can't
/// be written, the code isn't switched either.
std::optional<std::string> SetOn(u32 index, bool on);

/// Applies manual cheat index once: nullopt, or why not.
std::optional<std::string> ApplyOnce(u32 index);

/// Whether changing patch index to choice (-1: off) waits for the next start: the panel asks to reset now instead.
bool PatchNeedsReset(u32 index, s32 choice);

/// Changes patch index to choice, at once or at the next start (or now, with a reset, when reset_now), and keeps that
/// in the app's file: nullopt, or why not. When the file can't be written, nothing changes.
std::optional<std::string> SetPatch(u32 index, s32 choice, bool reset_now);

/// Puts value into a code's `?` nibbles: patch index's choice, or (patch false) cheat index; kept in the app's file.
std::optional<std::string> SetValue(bool patch, u32 index, s32 choice, u32 value);

/// Forgets the game's list, as the game ends.
void Clear();

} // namespace GameCodes
