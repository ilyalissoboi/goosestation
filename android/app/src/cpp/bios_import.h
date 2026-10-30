// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/file_system.h"

#include <optional>
#include <string>
#include <string_view>
#include <vector>

class Error;

namespace BIOSImport {

/// A BIOS image the core recognises: its file name, the core's description of it, and the console region it's made
/// for, by the core's name for it (Settings::GetConsoleRegionName(): "Auto" for one good for any region).
struct Image
{
  std::string file_name;
  std::string description;
  std::string region;
};

/// The images the core recognises at the top of dir, by BIOS::FindBIOSImagesInDirectory(), in file name order.
std::vector<Image> ListImages(std::string_view dir);

/// The description of the image the core picks for region_name ("NTSC-U", "NTSC-J" or "PAL") in dir when no image is
/// set for it: BIOS::FindBIOSImageInDirectory()'s. nullopt if it picks none, or one it doesn't recognise, or the name
/// isn't a region's.
std::optional<std::string> FindAutomaticImage(std::string_view region_name, std::string_view dir);

/// Copies the PS1 BIOS images the core recognises from the top of the folder at source_tree_uri (read through
/// provider) to dest_dir, then deletes every other file in dest_dir. Returns the images' descriptions in file name
/// order. A folder with no image gives an empty list, and nothing is deleted. Returns nullopt if the folder can't be
/// read or a copy fails, and then nothing is deleted either.
std::optional<std::vector<std::string>> Import(FileSystem::ContentProvider& provider, std::string_view source_tree_uri,
                                               std::string_view dest_dir, Error* error);

} // namespace BIOSImport
