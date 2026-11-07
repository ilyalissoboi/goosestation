// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "postprocessing_browser.h"

#include "common/string_util.h"

#include <algorithm>

namespace PostProcessing::Browser {
namespace {

// Whether path is inside folder (at any depth); sets rest to what follows the folder's "/".
bool IsInside(std::string_view path, std::string_view folder, std::string_view* rest)
{
  if (folder.empty())
  {
    *rest = path;
    return true;
  }
  if (path.size() <= folder.size() || !path.starts_with(folder) || path[folder.size()] != '/')
    return false;
  *rest = path.substr(folder.size() + 1);
  return true;
}

bool ContainsNoCase(std::string_view haystack, std::string_view needle)
{
  if (needle.size() > haystack.size())
    return false;
  for (size_t i = 0; i + needle.size() <= haystack.size(); i++)
  {
    if (StringUtil::EqualNoCase(haystack.substr(i, needle.size()), needle))
      return true;
  }
  return false;
}

} // namespace

Listing List(std::span<const AvailableShader> shaders, std::string_view folder)
{
  Listing listing;
  for (size_t i = 0; i < shaders.size(); i++)
  {
    const std::string path = GetBrowserPath(shaders[i]);
    std::string_view rest;
    if (!IsInside(path, folder, &rest))
      continue;

    const size_t slash = rest.find('/');
    if (slash == std::string_view::npos)
    {
      listing.shaders.push_back(i);
      continue;
    }

    const std::string_view child = rest.substr(0, slash);
    if (std::find(listing.folders.begin(), listing.folders.end(), child) == listing.folders.end())
      listing.folders.emplace_back(child);
  }

  std::sort(listing.folders.begin(), listing.folders.end(), [](const std::string& lhs, const std::string& rhs) {
    return StringUtil::Strcasecmp(lhs.c_str(), rhs.c_str()) < 0;
  });
  return listing;
}

std::vector<size_t> Search(std::span<const AvailableShader> shaders, std::string_view text)
{
  std::vector<size_t> matches;
  const std::string_view needle = StringUtil::StripWhitespace(text);
  if (needle.empty())
    return matches;

  for (size_t i = 0; i < shaders.size(); i++)
  {
    if (ContainsNoCase(GetBrowserPath(shaders[i]), needle))
      matches.push_back(i);
  }
  return matches;
}

std::string Parent(std::string_view folder)
{
  return FolderOf(folder);
}

std::string FolderOf(std::string_view browser_path)
{
  const size_t slash = browser_path.rfind('/');
  return (slash == std::string_view::npos) ? std::string() : std::string(browser_path.substr(0, slash));
}

std::string LeafName(const AvailableShader& shader)
{
  const std::string path = GetBrowserPath(shader);
  const size_t slash = path.rfind('/');
  std::string_view leaf = (slash == std::string::npos) ? std::string_view(path) : std::string_view(path).substr(slash + 1);
  constexpr std::string_view extension = ".slangp";
  if (leaf.size() > extension.size() && StringUtil::EqualNoCase(leaf.substr(leaf.size() - extension.size()), extension))
    leaf.remove_suffix(extension.size());
  return std::string(leaf);
}

std::string OpenFolder(std::span<const AvailableShader> shaders, std::string_view last)
{
  std::string folder(last);
  while (!folder.empty())
  {
    const Listing listing = List(shaders, folder);
    if (!listing.folders.empty() || !listing.shaders.empty())
      return folder;
    folder = Parent(folder);
  }
  return folder;
}

} // namespace PostProcessing::Browser
