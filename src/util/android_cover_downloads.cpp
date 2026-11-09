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

// The extensions GameList::GetCoverImagePath looks for.
constexpr std::array<std::string_view, 4> COVER_EXTENSIONS = {"jpg", "jpeg", "png", "webp"};

std::string Lower(std::string_view text)
{
  std::string lower(text);
  std::transform(lower.begin(), lower.end(), lower.begin(),
                 [](char c) { return (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c; });
  return lower;
}

bool IsCoverExtension(std::string_view extension)
{
  return std::find(COVER_EXTENSIONS.begin(), COVER_EXTENSIONS.end(), extension) != COVER_EXTENSIONS.end();
}

// The extension of the URL's last path segment, lower-cased, or empty.
std::string URLExtension(std::string_view url)
{
  url = url.substr(0, std::min(url.find('?'), url.find('#')));
  const std::string_view::size_type scheme = url.find("://");
  if (scheme != std::string_view::npos)
    url.remove_prefix(scheme + 3);
  const std::string_view::size_type slash = url.find('/');
  if (slash == std::string_view::npos)
    return {};
  const std::string_view segment = url.substr(url.rfind('/') + 1);
  const std::string_view::size_type dot = segment.rfind('.');
  return (dot == std::string_view::npos) ? std::string() : Lower(segment.substr(dot + 1));
}

bool CanBeFileName(const std::string& name)
{
  return !name.empty() && Path::SanitizeFileName(name) == name;
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

std::optional<std::string> CoverExtension(std::string_view content_type, std::string_view url)
{
  const std::string type = Lower(StringUtil::StripWhitespace(content_type.substr(0, content_type.find(';'))));
  if (!type.empty() && !type.starts_with("image/") && type != "application/octet-stream")
    return std::nullopt;

  if (std::string extension = HTTPDownloader::GetExtensionForContentType(type); IsCoverExtension(extension))
    return extension;
  if (std::string extension = URLExtension(url); IsCoverExtension(extension))
    return extension;
  return "jpg";
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
  std::vector<size_t> wanted;
  for (size_t i = 0; i < targets.size(); i++)
  {
    if (!hooks.has_cover(i))
      wanted.push_back(i);
  }
  summary.total = static_cast<u32>(wanted.size());

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
    bool done = false;
    for (const std::string& source : sources)
    {
      if (!HasVariable(source))
        continue;

      const std::string url = ExpandSource(source, target);
      const Response response = hooks.fetch(url);
      tried = true;
      if (response.status == HTTPDownloader::HTTP_STATUS_CANCELLED || hooks.cancelled())
      {
        summary.cancelled = true;
        break;
      }
      if (response.status < 0)
        continue;

      answered = true;
      if (response.status != HTTPDownloader::HTTP_STATUS_OK || response.data.empty())
        continue;
      const std::optional<std::string> extension = CoverExtension(response.content_type, url);
      if (!extension.has_value())
        continue;

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
      done = true;
      break;
    }

    if (summary.cancelled)
      break;
    if (!done)
    {
      if (tried && !answered)
        summary.unreachable++;
      else
        summary.missing++;
    }
  }

  return summary;
}

} // namespace AndroidCoverDownloads
