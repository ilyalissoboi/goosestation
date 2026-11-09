// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <functional>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

/// Android's cover downloads: for each library tile without a cover, the player's sources tried in order, the first
/// image saved under the name the core's cover lookup finds. The network, the files and the library come in through
/// Hooks, so it holds no Android headers and is tested on every platform.
namespace AndroidCoverDownloads {

/// Whether a source holds `${serial}`, `${title}` or `${filetitle}`: one without would fetch one image for every game.
bool HasVariable(std::string_view source);

/// A library tile: its key (the serial, or the core's `HASH-…` ID), its title, `Path::GetFileTitle()` of its path (as
/// the cover lookup tries it), and the file title of its display name (for `${filetitle}`, as the core makes it).
struct Target
{
  std::string key;
  std::string title;
  std::string path_title;
  std::string display_title;
};

/// [source] with each variable replaced by the URL-encoded key, title or display title.
std::string ExpandSource(std::string_view source, const Target& target);

/// The extension to save an answer as, from the image's own bytes: jpg, png or webp, the formats the library reads.
/// Nullopt for anything else (a web page, another format), whatever the server said it was.
std::optional<std::string> CoverExtension(std::span<const u8> data);

/// The cover's file name: by the key when [use_serial] (unless it's the core's `HASH-…` ID, which isn't a serial),
/// else by the title, else by the key, else by the path's file title, the first that can be a file name as it is on
/// FAT and Windows, whatever the platform (as the core's GetNewCoverImagePathForEntry names one, which saves by an
/// empty serial instead of falling back, and goes by the host's own rules). Nullopt when none can.
std::optional<std::string> CoverFileName(const Target& target, bool use_serial, std::string_view extension);

/// An answer: its HTTP status (or HTTPDownloader's negative ones) and body.
struct Response
{
  s32 status = 0;
  std::vector<u8> data;
};

/// What Run() asks of the platform, by the target's index. save() returns the path written, or nullopt if it wasn't
/// (a file already there, a write that failed). progress() is called as each game starts, its position counted from 1.
struct Hooks
{
  std::function<bool(size_t index)> has_cover;
  std::function<Response(const std::string& url)> fetch;
  std::function<std::optional<std::string>(size_t index, const std::string& file_name, std::span<const u8> data)> save;
  std::function<void(size_t index, u32 position, u32 total)> progress;
  std::function<void(size_t index, const std::string& saved_path)> found;
  std::function<bool()> cancelled;
};

/// How a run went: the library's games, those without a cover at its start, and how many of them got one, had none at
/// the sources, had no source answer at all, or got one that couldn't be saved; and whether it was cancelled.
struct Summary
{
  u32 games = 0;
  u32 total = 0;
  u32 found = 0;
  u32 missing = 0;
  u32 unreachable = 0;
  u32 unsaved = 0;
  bool cancelled = false;
};

/// How many games in a row a source may leave unanswered (no connection, a timeout) before the run stops asking it.
inline constexpr u32 UNANSWERED_IN_A_ROW = 3;

/// Fetches a cover for each of [targets] without one, trying [sources] in order and stopping at the first image. A
/// source is skipped without a variable, for a game where one of its variables is empty (`${serial}` with no serial or
/// only a `HASH-` ID), and for the rest of the run once it has left [UNANSWERED_IN_A_ROW] games in a row unanswered. A
/// game whose cover appears before its turn, or while its image is fetched, is skipped; one is never replaced.
Summary Run(std::span<const Target> targets, std::span<const std::string> sources, bool use_serial,
            const Hooks& hooks);


} // namespace AndroidCoverDownloads
