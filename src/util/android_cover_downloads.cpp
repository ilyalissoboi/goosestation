// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_cover_downloads.h"
#include "http_downloader.h"

#include "common/path.h"
#include "common/string_util.h"

#include <algorithm>
#include <array>

namespace AndroidCoverDownloads {

namespace {

constexpr std::array<std::string_view, 3> VARIABLES = {"${serial}", "${title}", "${filetitle}"};

// Whether [data] starts with [magic] at [offset].
bool StartsWith(std::span<const u8> data, size_t offset, std::string_view magic)
{
  return data.size() >= offset + magic.size() &&
         std::equal(magic.begin(), magic.end(), data.begin() + offset,
                    [](char m, u8 d) { return static_cast<u8>(m) == d; });
}

// Whether a source asks for a value [target] doesn't have: then there's nothing to ask it.
bool AsksForAnEmptyValue(std::string_view source, const Target& target)
{
  const bool no_serial = target.key.empty() || target.key.starts_with("HASH-");
  return (no_serial && source.find("${serial}") != std::string_view::npos) ||
         (target.title.empty() && source.find("${title}") != std::string_view::npos) ||
         (target.display_title.empty() && source.find("${filetitle}") != std::string_view::npos);
}

// A name the data folder's storage (FAT, through the storage provider) and Windows can both hold, wherever this runs:
// Path::SanitizeFileName() keeps what the host allows, which on Android is all but '/' and '*'.
bool CanBeFileName(const std::string& name)
{
  if (name.empty() || name.back() == '.' || name.back() == ' ')
    return false;
  return std::none_of(name.begin(), name.end(), [](char c) {
    return static_cast<unsigned char>(c) < 32 || std::string_view("<>:\"/\\|?*").find(c) != std::string_view::npos;
  });
}

} // namespace

bool HasVariable(std::string_view source)
{
  return std::any_of(VARIABLES.begin(), VARIABLES.end(),
                     [source](std::string_view variable) { return source.find(variable) != std::string_view::npos; });
}

std::string ExpandSource(std::string_view source, const Target& target)
{
  std::string url(source);
  StringUtil::ReplaceAll(&url, "${serial}", Path::URLEncode(target.key));
  StringUtil::ReplaceAll(&url, "${title}", Path::URLEncode(target.title));
  StringUtil::ReplaceAll(&url, "${filetitle}", Path::URLEncode(target.display_title));
  return url;
}

std::optional<std::string> CoverExtension(std::span<const u8> data)
{
  if (StartsWith(data, 0, "\xff\xd8\xff"))
    return "jpg";
  if (StartsWith(data, 0, "\x89PNG\r\n\x1a\n"))
    return "png";
  if (StartsWith(data, 0, "RIFF") && StartsWith(data, 8, "WEBP"))
    return "webp";
  return std::nullopt;
}

std::optional<std::string> CoverFileName(const Target& target, bool use_serial, std::string_view extension)
{
  // The core's ID for a file without a serial isn't one to name a cover by when asked for serials.
  const bool has_serial = !target.key.starts_with("HASH-");
  const std::array<const std::string*, 4> names = {(use_serial && has_serial) ? &target.key : nullptr, &target.title,
                                                   &target.key, &target.path_title};
  for (const std::string* name : names)
  {
    if (!name || !CanBeFileName(*name))
      continue;
    std::string file_name = *name + "." + std::string(extension);
    if (CanBeFileName(file_name))
      return file_name;
  }
  return std::nullopt;
}

Summary Run(std::span<const Target> targets, std::span<const std::string> sources, bool use_serial,
            const Hooks& hooks)
{
  Summary summary;
  summary.games = static_cast<u32>(targets.size());
  std::vector<size_t> wanted;
  for (size_t i = 0; i < targets.size(); i++)
  {
    if (!hooks.has_cover(i))
      wanted.push_back(i);
  }
  summary.total = static_cast<u32>(wanted.size());

  // Each source's games in a row without an answer; at UNANSWERED_IN_A_ROW it's asked no more.
  std::vector<u32> unanswered(sources.size(), 0);

  u32 position = 0;
  for (const size_t index : wanted)
  {
    position++;
    if (hooks.cancelled())
    {
      summary.cancelled = true;
      break;
    }
    // A cover that came while earlier games were fetched (the mirror, desktop) stays as it is.
    if (hooks.has_cover(index))
      continue;

    hooks.progress(index, position, summary.total);
    const Target& target = targets[index];
    bool answered = false;
    bool tried = false;
    bool dropped = false;
    bool done = false;
    for (size_t s = 0; s < sources.size(); s++)
    {
      const std::string& source = sources[s];
      if (!HasVariable(source) || AsksForAnEmptyValue(source, target))
        continue;
      if (unanswered[s] >= UNANSWERED_IN_A_ROW)
      {
        dropped = true;
        continue;
      }

      const std::string url = ExpandSource(source, target);
      const Response response = hooks.fetch(url);
      tried = true;
      if (response.status == HTTPDownloader::HTTP_STATUS_CANCELLED || hooks.cancelled())
      {
        summary.cancelled = true;
        break;
      }
      if (response.status < 0)
      {
        unanswered[s]++;
        continue;
      }

      unanswered[s] = 0;
      answered = true;
      if (response.status != HTTPDownloader::HTTP_STATUS_OK)
        continue;
      const std::optional<std::string> extension = CoverExtension(response.data);
      if (!extension.has_value())
        continue;

      // A cover that came while the request was out (by another name or extension, which the lookup might prefer).
      done = true;
      if (hooks.has_cover(index))
        break;

      const std::optional<std::string> file_name = CoverFileName(target, use_serial, extension.value());
      const std::optional<std::string> saved =
        file_name.has_value() ? hooks.save(index, file_name.value(), response.data) : std::nullopt;
      if (saved.has_value())
      {
        summary.found++;
        hooks.found(index, saved.value());
      }
      else
      {
        summary.unsaved++;
      }
      break;
    }

    if (summary.cancelled)
      break;
    if (!done)
    {
      // No answer from any source asked, or every one that could be asked had stopped answering.
      if ((tried || dropped) && !answered)
        summary.unreachable++;
      else
        summary.missing++;
    }
  }

  return summary;
}

} // namespace AndroidCoverDownloads
