// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include <optional>
#include <string>
#include <string_view>

class SettingsInterface;

namespace AndroidHost {

/// A settings.ini from desktop, another Android install or a reinstall has an [Android] InstallID other than
/// install_id. Such a file keeps its settings, except those that only make sense where it came from: the renderer,
/// the game folders, the BIOS images and folder, the controller bindings (with [Android] ControllerDescriptors, the
/// indices they're for), and folders outside EmuFolders::DataRoot, which go back to their defaults. InstallID is then
/// set to install_id. Returns whether anything changed; the caller holds the settings lock and saves.
bool AdaptForeignSettings(SettingsInterface& si, std::string_view install_id);

/// path relative to EmuFolders::DataRoot, with '/' separators: empty for the data root itself, and nullopt for a path
/// outside it, or one from Windows. A relative path is relative to the data root. Symbolic links count as resolved.
std::optional<std::string> RelativeToDataRoot(std::string_view path);

} // namespace AndroidHost
