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

/// Copies the PS1 BIOS images the core recognises from the top of the folder at source_tree_uri (read through
/// provider) to dest_dir, then deletes every other file in dest_dir. Returns the images' descriptions in file name
/// order. A folder with no image gives an empty list, and nothing is deleted. Returns nullopt if the folder can't be
/// read or a copy fails, and then nothing is deleted either.
std::optional<std::vector<std::string>> Import(FileSystem::ContentProvider& provider, std::string_view source_tree_uri,
                                               std::string_view dest_dir, Error* error);

} // namespace BIOSImport
