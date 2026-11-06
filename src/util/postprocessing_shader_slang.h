// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace PostProcessing::Slang {

/// Whether a stage's name is a slang preset's: it ends with ".slangp" (any case).
bool IsPresetName(std::string_view name);

/// The preset's absolute path under the shaders folder, or empty when the name is empty, absolute, or has a ".."
/// component, so a stage name never reaches outside the folder. '\' is taken as '/'.
std::string ResolvePresetPath(std::string_view shaders_root, std::string_view name);

/// Every preset under the folder, relative with '/' separators, sorted. Skips anything under "__MACOSX" and files or
/// folders whose names start with '.'. Reads directory entries only, never a preset.
std::vector<std::string> FindPresets(std::string_view shaders_root);

} // namespace PostProcessing::Slang
