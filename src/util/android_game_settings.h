// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include <functional>
#include <string>
#include <string_view>
#include <vector>

class SettingsInterface;

/// A game's own settings (gamesettings/<serial>.ini) as Android takes them: the core layers them over settings.ini,
/// and some of what desktop writes there can't work here.
namespace AndroidGameSettings {

/// What Android can use: the keys of every controller type's settings ([PadN] beside Type), the renderers it has (by
/// their settings.ini names), and whether a BIOS image of that name is in its BIOS folder.
struct Usable
{
  std::vector<std::string> pad_setting_keys;
  std::vector<std::string> renderers;
  std::function<bool(std::string_view)> has_bios;
};

/// Drops from a game's loaded settings, in memory, what can't work on Android, so settings.ini's own apply instead:
/// [ControllerPorts] InputProfileName; [InputSources] and [Hotkeys]; every [Pad1] to [Pad8] key but Type and the
/// types' settings (the bindings and macros, desktop's devices); a [GPU] Renderer it doesn't have; a [BIOS] PathNTSCJ,
/// PathNTSCU or PathPAL it hasn't, and SearchDirectory; an absolute [MemoryCards] Directory. Never saved.
void Adapt(SettingsInterface& si, const Usable& usable);

/// Whether [si] sets anything for the controllers: a [ControllerPorts] key other than UseGameSettingsForController and
/// GameSettingsInitialized, or any [PadN] key.
bool HasControllerKeys(SettingsInterface& si);

} // namespace AndroidGameSettings
