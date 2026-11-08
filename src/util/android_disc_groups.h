// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

/// Groups the game list's files into the games the Android library shows: a playlist and the discs it lists, a
/// database disc set, or discs whose file names differ only by a disc marker. It holds no Android headers and knows
/// nothing of the game list, so it builds and is tested on every platform.
namespace AndroidDiscGroups {

/// A file title's disc marker taken out: what remains (its spaces collapsed), and the disc's number.
struct DiscMarker
{
  std::string base;
  u32 number;
};

/// The last disc marker in a file title: `disc` or `cd` in any case, then nothing, a space or `_`, then a number from
/// 1 to 99, then optionally ` of ` and a number. In brackets, `(…)` or `[…]`, nothing else is inside them; bare, it
/// follows the start of the title or a space, `-` or `_`, and those separators go with it. Nullopt without one.
std::optional<DiscMarker> ParseDiscMarker(std::string_view file_title);

/// The title with its disc marker taken out, or the title as it is when it has none.
std::string StripDiscMarker(std::string_view title);

/// An M3U playlist's entries, as the core's M3U reader takes them from its text: trimmed lines, without blank lines and
/// `#` comments, each a native path, and a relative one resolved beside the playlist.
std::vector<std::string> ParsePlaylist(std::string_view m3u_path, std::string_view text);

enum class FileType
{
  Disc,
  Playlist,
  Other,
};

/// One file of the game list: its type, path, serial (the core's ID, `HASH-…` for a disc without a serial) and title;
/// its place in a database disc set, if any; whether the database knows its serial; and a playlist's entries.
struct ListedFile
{
  FileType type = FileType::Other;
  std::string path;
  std::string serial;
  std::string title;
  std::string disc_set_name;
  s32 disc_set_index = -1;
  bool known = false;
  std::vector<std::string> playlist_paths;
};

/// One disc of a game, numbered as its set, playlist or marker numbers it.
struct Disc
{
  std::string path;
  std::string serial;
  std::string title;
  u32 number;
};

/// A game as the library shows it: its key (the serial its save states are kept under), title and path (the
/// playlist's, the lowest disc's, or the file's); whether it's a playlist, whose discs are sub-images; its discs in
/// number order (one for a single disc, none for a PS-EXE or a PSF); and the indices of the listed files it stands for.
struct Game
{
  std::string key;
  std::string title;
  std::string path;
  bool playlist = false;
  std::vector<Disc> discs;
  std::vector<size_t> files;
};

/// The games [files] make, in the order of their first file; each file is in exactly one. Playlists come first, each
/// claiming the listed discs it names. Database sets are made from the rest, as the core makes them: disc 1 and
/// another disc, a second file for one disc staying apart. Then discs the database doesn't know are grouped by name: in
/// one folder, with the same base name ignoring case, and no disc number twice. Anything left is a game of its own.
std::vector<Game> GroupFiles(std::span<const ListedFile> files);

/// The number of [game]'s disc a save state was made on, from what the state recorded: an M3U's sub-image; else the one
/// disc with its serial; else the disc whose file name is the same, ignoring case. Nullopt when none matches.
std::optional<u32> FindStateDisc(const Game& game, std::string_view serial, std::string_view media_path,
                                 u32 subimage_index);

/// Whether a save state is [game]'s, from what the state recorded, and if so the number of the disc it was made on (0
/// for a game without discs, or a disc that isn't known). It is when: its serial is [game]'s key or one of its discs'
/// serials; or, when it records no serial or [game] has none known, its media is an M3U named as [game]'s playlist, or
/// its media's file name is [game]'s or one of its discs', ignoring case; or it records no serial and no media, and
/// [game] has no key and no discs, and its [title] is the file title of [game]'s path, as the core titles a PSF (or the
/// BIOS alone, with no path and no title).
std::optional<u32> MatchStateGame(const Game& game, std::string_view serial, std::string_view title,
                                  std::string_view media_path, u32 subimage_index);

} // namespace AndroidDiscGroups
