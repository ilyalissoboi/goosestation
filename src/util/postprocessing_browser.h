// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "postprocessing.h"

#include <span>
#include <string>
#include <string_view>
#include <vector>

/// The shader browser's folders and search, over GetAvailableShaders(). Folders are '/'-separated browser paths
/// (GetBrowserPath()); "" is the top.
namespace PostProcessing::Browser {

struct Listing
{
  /// The folder's child folders, by name, sorted ignoring case.
  std::vector<std::string> folders;
  /// The shaders directly in the folder, as indices into the list, in its order.
  std::vector<size_t> shaders;
};

Listing List(std::span<const AvailableShader> shaders, std::string_view folder);

/// Every shader whose browser path contains the text (trimmed, ignoring case), in the list's order; none for no text.
std::vector<size_t> Search(std::span<const AvailableShader> shaders, std::string_view text);

/// The folder above, "" for the top.
std::string Parent(std::string_view folder);

/// The folder a browser path is in.
std::string FolderOf(std::string_view browser_path);

/// The shader's own name in its folder: the browser path's last component, without ".slangp".
std::string LeafName(const AvailableShader& shader);

/// The folder to open on: last if it holds anything, else its nearest folder that does, else the top.
std::string OpenFolder(std::span<const AvailableShader> shaders, std::string_view last);

} // namespace PostProcessing::Browser
