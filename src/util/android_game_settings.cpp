// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_game_settings.h"

#include "common/path.h"
#include "common/settings_interface.h"

#include "fmt/format.h"

#include <algorithm>
#include <array>

namespace AndroidGameSettings {

// The core's eight pads, [Pad1] to [Pad8] (NUM_CONTROLLER_AND_CARD_PORTS).
static constexpr int NUM_PADS = 8;
static constexpr const char* CONTROLLER_PORTS = "ControllerPorts";

static bool Contains(const std::vector<std::string>& list, std::string_view value)
{
  return std::find(list.begin(), list.end(), value) != list.end();
}

void Adapt(SettingsInterface& si, const Usable& usable)
{
  si.DeleteValue(CONTROLLER_PORTS, "InputProfileName");
  si.RemoveSection("InputSources");
  si.RemoveSection("Hotkeys");

  for (int pad = 1; pad <= NUM_PADS; pad++)
  {
    const std::string section = fmt::format("Pad{}", pad);
    for (const auto& [key, value] : si.GetKeyValueList(section.c_str()))
    {
      if (key != "Type" && !Contains(usable.pad_setting_keys, key))
        si.DeleteValue(section.c_str(), key.c_str());
    }
  }

  if (std::string renderer; si.GetStringValue("GPU", "Renderer", &renderer) && !Contains(usable.renderers, renderer))
    si.DeleteValue("GPU", "Renderer");

  for (const char* key : {"PathNTSCJ", "PathNTSCU", "PathPAL"})
  {
    std::string name;
    if (si.GetStringValue("BIOS", key, &name) && !name.empty() && !(usable.has_bios && usable.has_bios(name)))
      si.DeleteValue("BIOS", key);
  }
  si.DeleteValue("BIOS", "SearchDirectory");

  if (std::string folder; si.GetStringValue("MemoryCards", "Directory", &folder) && Path::IsAbsolute(folder))
    si.DeleteValue("MemoryCards", "Directory");

  si.RemoveEmptySections();
}

bool HasControllerKeys(SettingsInterface& si)
{
  for (const auto& [key, value] : si.GetKeyValueList(CONTROLLER_PORTS))
  {
    if (key != "UseGameSettingsForController" && key != "GameSettingsInitialized")
      return true;
  }

  for (int pad = 1; pad <= NUM_PADS; pad++)
  {
    if (!si.GetKeyValueList(fmt::format("Pad{}", pad).c_str()).empty())
      return true;
  }

  return false;
}

} // namespace AndroidGameSettings
