// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

// Post-processing favourites: saved display chains, one file each in a folder (EmuFolders::PostProcessing), holding the
// chain as settings.ini does ([PostProcessing] and its [PostProcessing/StageN] sections). A favourite's name is its
// file's name without ".ini"; two names that differ only in case are the same name. Their order is a string list in
// settings.ini (the order layer here): the names it lists whose files exist, in its order, then the folder's other
// files, by name ignoring case.

#include <optional>
#include <string>
#include <string_view>
#include <vector>

class Error;
class SettingsInterface;

namespace PostProcessing::Favourites {

static constexpr const char* ORDER_SECTION = "PostProcessingFavourites";
static constexpr const char* ORDER_KEY = "Order";

struct Info
{
  std::string name;
  /// Each stage's shader, in order.
  std::vector<std::string> shaders;
  /// Those of shaders that aren't installed here (IsShaderInstalled()).
  std::vector<std::string> missing;
};

/// Whether name can be a favourite's: trimmed of spaces (into trimmed), not empty, not starting with a dot, and
/// without / \ : * ? " < > | or a control character.
bool CheckName(std::string_view name, std::string* trimmed, Error* error);

/// Every favourite in folder, in order's order.
std::vector<Info> List(const std::string& folder, const SettingsInterface& order);

/// The favourite whose name is name, ignoring case: its name as listed.
std::optional<std::string> Find(const std::string& folder, const SettingsInterface& order, std::string_view name);

/// Saves chain's display chain as the favourite name, on. A new name goes last; with replace, a favourite of that name
/// is overwritten, keeping its place and its name's case; without, it's refused.
bool Save(const std::string& folder, SettingsInterface& order, std::string_view name, const SettingsInterface& chain,
          bool replace, Error* error);

/// Renames a favourite, keeping its place. A rename that changes only the name's case is allowed.
bool Rename(const std::string& folder, SettingsInterface& order, std::string_view from, std::string_view to,
            Error* error);

/// Moves a favourite one place up or down the order; nothing at either end.
void Move(const std::string& folder, SettingsInterface& order, std::string_view name, bool down);

bool Delete(const std::string& folder, SettingsInterface& order, std::string_view name, Error* error);

/// Replaces to's display chain with the favourite's, on. Nothing else in to changes.
bool Apply(const std::string& folder, std::string_view name, SettingsInterface& to, Error* error);

/// Whether two layers' display chains are the same: their stages, each one's shader and options, whether or not
/// either is on.
bool ChainsEqual(const SettingsInterface& a, const SettingsInterface& b);

/// The favourite after (or before) the one chain is: last, while chain still equals it, else the first that chain
/// equals. When chain equals none, the first (or the last). Wraps; nullopt with no favourites.
std::optional<std::string> Step(const std::string& folder, const SettingsInterface& order,
                                const SettingsInterface& chain, std::string_view last, bool forward);

/// The hotkeys' step: from the running display chain to the next (or previous) favourite, written where that chain
/// is. That's game_file (loaded afresh, then saved) when game_layer, the game's settings layer, has a display chain of
/// its own, else base, settings.ini, which also holds the order (the caller saves it). last is the favourite applied
/// last, and becomes this one. wrote_game_file says which it wrote: the game's file (reload the game's settings) or
/// settings.ini (save it). nullopt with no favourites (error untouched) or when writing fails (error set). The
/// caller holds the settings lock.
std::optional<std::string> StepChain(const std::string& folder, SettingsInterface& base,
                                     const SettingsInterface* game_layer, const std::string& game_file,
                                     std::string* last, bool forward, bool* wrote_game_file, Error* error);

/// What a favourite's row says under its name: "Not installed: a, b" when shaders are missing, "No shaders" for an
/// empty chain, else its shaders joined with " · ".
std::string Summary(const Info& info);

} // namespace PostProcessing::Favourites
