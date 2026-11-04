// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include <optional>
#include <string>
#include <string_view>

/// The Android memory card editor's rules that need no emulator state, so they build and are tested on every platform.
namespace AndroidMemcards {

/// The title a playlist's card is named after at boot (per-game-title cards), when it isn't the one
/// System::GetGameMemoryCardPath() gives: the playlist's own file title, when it differs from the database's title (not
/// empty), the playlist's title is used for cards, and no card under the database's title exists yet. Otherwise nullopt.
std::optional<std::string> PlaylistCardTitle(std::string_view playlist_title, std::string_view database_title,
                                             bool database_card_exists, bool use_playlist_title);

/// How a card file found in the memory card folder is named in the editor: its file name without ".mcd", and without
/// the "_1" of a game's first card.
std::string OtherCardName(std::string_view file_name);

} // namespace AndroidMemcards
