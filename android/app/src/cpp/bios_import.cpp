// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "bios_import.h"

#include "core/bios.h"
#include "core/settings.h"

#include "common/error.h"
#include "common/log.h"
#include "common/path.h"

#include <algorithm>

Log_SetChannel(BIOSImport);

namespace {
/// BIOS::FindBIOSImagesInDirectory()'s images that the core recognises, in file name order.
std::vector<std::pair<std::string, const BIOS::ImageInfo*>> FindKnownImages(const std::string& dir)
{
  std::vector<std::pair<std::string, const BIOS::ImageInfo*>> images = BIOS::FindBIOSImagesInDirectory(dir.c_str());
  std::erase_if(images, [](const auto& image) { return (image.second == nullptr); });
  std::sort(images.begin(), images.end(), [](const auto& lhs, const auto& rhs) { return (lhs.first < rhs.first); });
  return images;
}
} // namespace

std::vector<BIOSImport::Image> BIOSImport::ListImages(std::string_view dir)
{
  std::vector<Image> result;
  for (const auto& [name, info] : FindKnownImages(std::string(dir)))
    result.push_back({name, info->description, Settings::GetConsoleRegionName(info->region)});

  return result;
}

std::optional<std::string> BIOSImport::FindAutomaticImage(std::string_view region_name, std::string_view dir)
{
  const std::optional<ConsoleRegion> region = Settings::ParseConsoleRegionName(std::string(region_name).c_str());
  if (!region.has_value())
    return std::nullopt;

  const std::optional<BIOS::Image> image = BIOS::FindBIOSImageInDirectory(region.value(), std::string(dir).c_str(),
                                                                          nullptr);
  if (!image.has_value() || !image->info)
    return std::nullopt;

  return std::string(image->info->description);
}

std::optional<std::vector<std::string>> BIOSImport::Import(FileSystem::ContentProvider& provider,
                                                           std::string_view source_tree_uri, std::string_view dest_dir,
                                                           Error* error)
{
  const std::string source(source_tree_uri);
  const std::string dest(dest_dir);

  // FindBIOSImagesInDirectory() finds nothing in a folder it can't read, and finding nothing deletes every copy, so
  // check that the folder can be read first. The bridge caches the listing, so the search below reuses it.
  FileSystem::FindResultsArray entries;
  if (!provider.ListDirectory(source, &entries, error))
    return std::nullopt;

  const std::vector<std::pair<std::string, const BIOS::ImageInfo*>> images = FindKnownImages(source);

  // A folder with no BIOS replaces nothing: the copies already made keep working.
  if (images.empty())
    return std::vector<std::string>();

  if (!FileSystem::EnsureDirectoryExists(dest.c_str(), true, error))
    return std::nullopt;

  std::vector<std::string> descriptions;
  descriptions.reserve(images.size());
  for (const auto& [name, info] : images)
  {
    const std::optional<DynamicHeapArray<u8>> data =
      FileSystem::ReadBinaryFile(Path::Combine(source, name).c_str(), error);
    if (!data || !FileSystem::WriteAtomicRenamedFile(Path::Combine(dest, name), data->data(), data->size(), error))
    {
      Error::AddPrefixFmt(error, "Can't copy {}: ", name);
      return std::nullopt;
    }

    descriptions.emplace_back(info->description);
  }

  // The copies are made, so a file that can't be deleted only leaves a stale image behind.
  FileSystem::FindResultsArray existing;
  FileSystem::FindFiles(dest.c_str(), "*",
                        FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_HIDDEN_FILES | FILESYSTEM_FIND_RELATIVE_PATHS,
                        &existing);
  for (const FILESYSTEM_FIND_DATA& fd : existing)
  {
    if (std::ranges::any_of(images, [&fd](const auto& image) { return (image.first == fd.FileName); }))
      continue;

    Error delete_error;
    if (!FileSystem::DeleteFile(Path::Combine(dest, fd.FileName).c_str(), &delete_error))
      WARNING_LOG("Can't delete {}: {}", fd.FileName, delete_error.GetDescription());
  }

  return descriptions;
}
