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

/// The extension to save an answer as: the content type's, else the URL's last path segment's, when it's one the
/// library reads (jpg, jpeg, png, webp), else jpg. Nullopt when the content type is present and is neither an image nor
/// `application/octet-stream`: a web page, not a cover.
std::optional<std::string> CoverExtension(std::string_view content_type, std::string_view url);

/// The cover's file name: by the key when [use_serial], else by the title, else by the key, else by the path's file
/// title, the first that can be a file name as it is (as the core's GetNewCoverImagePathForEntry names one, which saves
/// by an empty serial instead of falling back). Nullopt when none can.
std::optional<std::string> CoverFileName(const Target& target, bool use_serial, std::string_view extension);

/// An answer: its HTTP status (or HTTPDownloader's negative ones), content type and body.
struct Response
{
  s32 status = 0;
  std::string content_type;
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

/// How a run went: the games without a cover at its start, and how many of them got one, had none at the sources, had
/// no source answer at all, or got one that couldn't be saved; and whether it was cancelled.
struct Summary
{
  u32 total = 0;
  u32 found = 0;
  u32 missing = 0;
  u32 unreachable = 0;
  u32 unsaved = 0;
  bool cancelled = false;
};

/// Fetches a cover for each of [targets] without one, trying [sources] in order (skipping one without a variable) and
/// stopping at the first image. A game whose cover appears before its turn is skipped; one is never replaced.
Summary Run(std::span<const Target> targets, std::span<const std::string> sources, bool use_serial,
            const Hooks& hooks);

} // namespace AndroidCoverDownloads
