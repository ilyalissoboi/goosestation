// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "util/android_disc_groups.h"

#include "common/types.h"

#include <optional>
#include <string>
#include <vector>

/// The running game's patch codes: one list for the whole game, from the player's .cht files and the database's codes
/// for every disc, which the core is given again after every disc change. Which codes are on is kept in the app's own
/// file, cheats/<key>.patchcodes; the player's files are only read. Everything here runs on the CPU thread.
namespace GameCodes {

/// A code as the app shows it: its group (empty for none), name, whether it's applied by hand, and whether it's on.
struct Code
{
  std::string group;
  std::string name;
  bool manual;
  bool on;
};

/// Builds game's list, before it boots.
void Load(const AndroidDiscGroups::Game& game);

/// Gives the core the game's list, as each UpdateRunningGame() drops the one it had (Host::OnGameChanged()).
void Apply();

/// The game's codes, in the list's order.
std::vector<Code> List();

/// The player's file the panel names for adding codes: `<game title>.cht`, in a form file systems take.
std::string AddFileName();

/// Switches code index on or off, at once, and keeps that in the app's file: nullopt, or why the file couldn't be
/// written (the code stays switched for this session) or the code can't be switched.
std::optional<std::string> SetOn(u32 index, bool on);

/// Applies manual code index once: nullopt, or why not.
std::optional<std::string> ApplyOnce(u32 index);

/// Forgets the game's list, as the game ends.
void Clear();

} // namespace GameCodes
