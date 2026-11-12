// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_host_state.h"

#include "android_host.h"
#include "achievements_bridge.h"
#include "emu_thread.h"
#include "foreign_settings.h"
#include "game_codes.h"
#include "core/achievements.h"
#include "core/achievements_offline.h"
#include "core/analog_controller.h"
#include "core/controller.h"
#include "core/fullscreen_ui.h"
#include "core/game_database.h"
#include "core/game_list.h"
#include "core/host.h"
#include "core/memcard_sync.h"
#include "core/memory_card.h"
#include "core/pad.h"
#include "core/settings.h"
#include "core/system.h"
#include "util/android_game_settings.h"
#include "util/android_input_keys.h"
#include "util/android_input_source.h"
#include "util/gpu_device.h"
#include "util/imgui_fullscreen.h"
#include "util/imgui_manager.h"
#include "util/ini_settings_interface.h"
#include "util/input_manager.h"
#include "util/postprocessing.h"
#include "util/postprocessing_shader_slang.h"
#include "util/window_info.h"
#include "common/memory_settings_interface.h"
#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/path.h"
#include "common/small_string.h"
#include "common/string_util.h"
#include "fmt/format.h"
#include <android/native_window.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <optional>
#include <utility>
#include <vector>

Log_SetChannel(AndroidHost);

namespace AndroidHost {

using AndroidInputKeys::DEVICE_PREFIX;

using AndroidInputKeys::ParseDeviceIndex;

static std::optional<u32> FindDescriptorIndex(std::string_view descriptor);

static const Controller::ControllerInfo* GetPadControllerInfo(SettingsInterface& si, u32 pad);

static std::vector<const Controller::ControllerInfo*> GetGameTypes(u32 pad);

static std::string GetPortLabel(u32 pad, const std::array<bool, 2>& multitap);

static bool PadHasAnalogButton(SettingsInterface& si, u32 pad);
static std::vector<const HotkeyInfo*> GetOfferedHotkeys(SettingsInterface& si, u32 pad);

// The DualShock's Analog button, Port 1's, which the app presses with a hotkey.
static constexpr std::string_view ANALOG_BUTTON = "Analog";

// The hotkey that presses it: the host's own (g_host_hotkeys), so it's a chord as the core's hotkeys are. A pad binding
// would fire on each of a chord's keys.
static constexpr std::string_view ANALOG_HOTKEY = "ToggleAnalogMode";

// Hotkeys the Controllers page leaves out: the menu has Change disc and Reset with their questions. The core's
// per-slot and global-slot hotkeys aren't built on Android (hotkeys.cpp): the app's own save path has the slots.
static constexpr std::string_view LEFT_OUT_HOTKEYS[] = {"ChangeDisc", "Reset"};

static constexpr bool IsPadBindingType(InputBindingInfo::Type type)
{
  return type == InputBindingInfo::Type::Button || type == InputBindingInfo::Type::Axis ||
         type == InputBindingInfo::Type::HalfAxis;
}

} // namespace AndroidHost

std::optional<u32> AndroidHost::FindDescriptorIndex(std::string_view descriptor)
{
  const auto it = std::find(s_descriptors.begin(), s_descriptors.end(), descriptor);
  if (it == s_descriptors.end())
    return std::nullopt;

  return static_cast<u32>(it - s_descriptors.begin());
}

void AndroidHost::WriteDescriptors(SettingsInterface& si)
{
  si.SetStringList(DESCRIPTORS_SECTION, DESCRIPTORS_KEY, s_descriptors);
}

// Under the settings lock: Android-<index>'s pad, 0-based ([Android] ControllerPorts).
u32 AndroidHost::GetPadOfIndex(SettingsInterface& si, u32 index)
{
  return AndroidInputKeys::PadForIndex(si.GetStringList(DESCRIPTORS_SECTION, PORTS_KEY), index);
}

const Controller::ControllerInfo* AndroidHost::GetPadControllerInfo(SettingsInterface& si, u32 pad)
{
  const std::string section = Controller::GetSettingsSection(pad);
  const std::string type = si.GetStringValue(section.c_str(), "Type", Controller::GetDefaultPadType(pad));
  const Controller::ControllerInfo* info = Controller::GetControllerInfo(type);
  if (!info)
    WARNING_LOG("Port {} has an unknown controller type '{}'.", pad + 1, type);

  return info;
}

u32 AndroidHost::AddDefaultBindings(SettingsInterface& si, u32 index, u32 pad)
{
  return AddDefaultBindings(si, index, pad, GetPadControllerInfo(si, pad));
}

u32 AndroidHost::AddDefaultBindings(SettingsInterface& si, u32 index, u32 pad, const Controller::ControllerInfo* info)
{
  if (!info)
    return 0;

  // As InputManager::MapController(): the first binding string for each of the pad's inputs, for each input the
  // controller has none for, so its remaps stay as they are.
  const std::string section = Controller::GetSettingsSection(pad);
  const GenericInputBindingMapping mapping = AndroidInputKeys::GetDefaultMapping(index);
  u32 added = 0;
  for (const Controller::ControllerBindingInfo& bi : info->bindings)
  {
    if (bi.generic_mapping == GenericInputBinding::Unknown ||
        AndroidInputKeys::GetDeviceBinding(si.GetStringList(section.c_str(), bi.name), index).has_value())
    {
      continue;
    }

    const auto it = std::find_if(mapping.begin(), mapping.end(),
                                 [&bi](const auto& entry) { return entry.first == bi.generic_mapping; });
    if (it != mapping.end() && si.AddToStringList(section.c_str(), bi.name, it->second.c_str()))
      added++;
  }

  return added;
}

bool AndroidHost::HasDeviceBinding(SettingsInterface& si, u32 index, u32 pad)
{
  std::vector<std::string> bindings;
  for (auto& [key, value] : si.GetKeyValueList(Controller::GetSettingsSection(pad).c_str()))
    bindings.push_back(std::move(value));

  return AndroidInputKeys::GetDeviceBinding(bindings, index).has_value();
}

// CPU thread: gives the input source each controller's pad and rumble strength.
void AndroidHost::UpdateSourceControllers()
{
  AndroidInputSource* const source =
    static_cast<AndroidInputSource*>(InputManager::GetInputSourceInterface(InputSourceType::Android));
  if (!source)
    return;

  std::vector<u32> pads(s_descriptors.size());
  std::vector<float> strengths(s_descriptors.size());
  {
    const auto lock = Host::GetSettingsLock();
    SettingsInterface& si = *s_base_settings_interface;
    const std::vector<std::string> rumble = si.GetStringList(DESCRIPTORS_SECTION, RUMBLE_KEY);
    for (u32 i = 0; i < pads.size(); i++)
    {
      pads[i] = GetPadOfIndex(si, i);
      strengths[i] = static_cast<float>(AndroidInputKeys::RumbleForIndex(rumble, i)) / 100.0f;
    }
  }

  source->SetControllerPads(std::move(pads));
  source->SetRumbleStrengths(std::move(strengths));
}

std::optional<u32> AndroidHost::GetRumbleStrength(std::string descriptor)
{
  if (!s_initialized)
    return std::nullopt;

  std::optional<u32> result;
  EmuThread::RunOnThread(
    [&descriptor, &result]() {
      const std::optional<u32> index = FindDescriptorIndex(descriptor);
      if (!index.has_value())
        return;

      const auto lock = Host::GetSettingsLock();
      result = AndroidInputKeys::RumbleForIndex(
        s_base_settings_interface->GetStringList(DESCRIPTORS_SECTION, RUMBLE_KEY), index.value());
    },
    true);
  return result;
}

void AndroidHost::SetRumbleStrength(std::string descriptor, u32 percent)
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread(
    [&descriptor, percent]() {
      const std::optional<u32> index = FindDescriptorIndex(descriptor);
      if (!index.has_value())
        return;

      {
        const auto lock = Host::GetSettingsLock();
        SettingsInterface& si = *s_base_settings_interface;
        si.SetStringList(DESCRIPTORS_SECTION, RUMBLE_KEY,
                         AndroidInputKeys::SetRumbleForIndex(si.GetStringList(DESCRIPTORS_SECTION, RUMBLE_KEY),
                                                             index.value(), percent));
        Error error;
        if (!si.Save(&error))
          ERROR_LOG("Failed to save settings: {}", error.GetDescription());
      }

      UpdateSourceControllers();
    },
    true);
}

void AndroidHost::SetPadVibrationForTest(u32 pad, float large, float small)
{
  EmuThread::RunOnThread([pad, large, small]() { InputManager::SetPadVibrationIntensity(pad, large, small); }, true);
}

bool AndroidHost::AddControllerToPort(const std::string& identifier)
{
  const std::optional<u32> index = ParseDeviceIndex(identifier);
  if (!index.has_value())
    return false;

  // Mapped later than it connected: skip it if it's gone again.
  const auto devices = InputManager::EnumerateDevices();
  if (std::none_of(devices.begin(), devices.end(),
                   [&identifier](const auto& device) { return device.first == identifier; }))
  {
    return false;
  }

  // Its default bindings are added on its port, beside the other controllers' there, so a pad connected later (a
  // Bluetooth pad next to a handheld's own) works too.
  {
    const auto lock = Host::GetSettingsLock();
    SettingsInterface& si = *s_base_settings_interface;
    const u32 pad = GetPadOfIndex(si, index.value());
    u32 added = AddDefaultBindings(si, index.value(), pad);
    // And, in a game, the type its own settings give the port, so one connected mid-game isn't left unbound.
    if (System::IsValid())
    {
      added += AddDefaultBindings(si, index.value(), pad,
                                  Controller::GetControllerInfo(g_settings.controller_types[pad]));
    }
    if (added == 0)
      return true;

    INFO_LOG("Added {} bindings for {} to Port {}.", added, identifier, pad + 1);
    Error error;
    if (!si.Save(&error))
      ERROR_LOG("Failed to save settings: {}", error.GetDescription());
  }

  System::ReloadInputBindings();
  return true;
}

void AndroidHost::MapNewController(const std::string& identifier)
{
  const std::optional<u32> index = ParseDeviceIndex(identifier);
  if (!index.has_value())
    return;

  // A new controller, or one seen before with no binding of its own on its port, which would be dead. One with even a
  // single binding keeps what it has, so a remap survives.
  if (std::find(s_unmapped_indices.begin(), s_unmapped_indices.end(), index.value()) == s_unmapped_indices.end())
  {
    u32 added = 0;
    {
      const auto lock = Host::GetSettingsLock();
      SettingsInterface& si = *s_base_settings_interface;
      const u32 pad = GetPadOfIndex(si, index.value());
      if (HasDeviceBinding(si, index.value(), pad))
      {
        // In a game whose own settings give the port another type, that type's defaults, when the controller has
        // none of the inputs only that type has bound (it was off as the game started): one cleared on purpose stays
        // cleared.
        const Controller::ControllerInfo* running =
          System::IsValid() ? Controller::GetControllerInfo(g_settings.controller_types[pad]) : nullptr;
        const Controller::ControllerInfo* own = GetPadControllerInfo(si, pad);
        const std::string section = Controller::GetSettingsSection(pad);
        bool unbound = false;
        if (running && running != own)
        {
          bool any_own_input = false;
          unbound = true;
          for (const Controller::ControllerBindingInfo& bi : running->bindings)
          {
            if (own && std::any_of(own->bindings.begin(), own->bindings.end(),
                                   [&bi](const Controller::ControllerBindingInfo& o) {
                                     return std::string_view(o.name) == bi.name;
                                   }))
            {
              continue;
            }
            any_own_input = true;
            if (AndroidInputKeys::GetDeviceBinding(si.GetStringList(section.c_str(), bi.name), index.value()))
              unbound = false;
          }
          unbound = unbound && any_own_input;
        }
        if (unbound)
        {
          added = AddDefaultBindings(si, index.value(), pad, running);
          Error error;
          if (added > 0 && !si.Save(&error))
            ERROR_LOG("Failed to save settings: {}", error.GetDescription());
        }
        if (added == 0)
          return;
      }
    }
    if (added > 0)
    {
      INFO_LOG("Added {} bindings for {} for the game's controller type.", added, identifier);
      System::ReloadInputBindings();
      return;
    }
  }

  // One that's gone again stays unmapped, and gets its defaults when it's back.
  if (AddControllerToPort(identifier))
    std::erase(s_unmapped_indices, index.value());
}

// Calls f with each game's own settings file whose controller settings count ([ControllerPorts]
// UseGameSettingsForController, as System::GetControllerSettingsLayers() takes them). Not under the settings lock.
template<typename F>
static void ForEachControllerGameFile(const F& f)
{
  std::string folder;
  {
    // The CPU thread changes the folders under it (EmuFolders::Update()).
    const auto lock = Host::GetSettingsLock();
    folder = EmuFolders::GameSettings;
  }

  FileSystem::FindResultsArray files;
  FileSystem::FindFiles(folder.c_str(), "*.ini", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_HIDDEN_FILES, &files);
  std::sort(files.begin(), files.end(),
            [](const FILESYSTEM_FIND_DATA& a, const FILESYSTEM_FIND_DATA& b) { return a.FileName < b.FileName; });
  for (const FILESYSTEM_FIND_DATA& file : files)
  {
    INISettingsInterface game(file.FileName);
    if (game.Load() && game.GetBoolValue("ControllerPorts", "UseGameSettingsForController", false))
      f(game);
  }
}

// The controller types games' own files set for pad, each once, in the files' order. Not under the settings lock.
std::vector<const Controller::ControllerInfo*> AndroidHost::GetGameTypes(u32 pad)
{
  std::vector<const Controller::ControllerInfo*> types;
  ForEachControllerGameFile([pad, &types](SettingsInterface& game) {
    const std::string type = game.GetStringValue(Controller::GetSettingsSection(pad).c_str(), "Type");
    const Controller::ControllerInfo* info = type.empty() ? nullptr : Controller::GetControllerInfo(type);
    if (info && std::find(types.begin(), types.end(), info) == types.end())
      types.push_back(info);
  });
  return types;
}

std::optional<std::vector<AndroidHost::PadBinding>> AndroidHost::GetPadBindings(std::string descriptor)
{
  if (!s_initialized)
    return std::nullopt;

  std::optional<std::vector<PadBinding>> result;
  EmuThread::RunOnThread(
    [&descriptor, &result]() {
      const std::optional<u32> index = FindDescriptorIndex(descriptor);
      if (!index.has_value())
        return;

      u32 pad;
      {
        const auto lock = Host::GetSettingsLock();
        pad = GetPadOfIndex(*s_base_settings_interface, index.value());
      }
      // The types games' own files give the port: their inputs are listed too, after its own type's, to remap them.
      const std::vector<const Controller::ControllerInfo*> game_types = GetGameTypes(pad);

      const auto lock = Host::GetSettingsLock();
      SettingsInterface& si = *s_base_settings_interface;
      result.emplace();
      const Controller::ControllerInfo* info = GetPadControllerInfo(si, pad);
      std::vector<const Controller::ControllerInfo*> types;
      if (info)
        types.push_back(info);
      for (const Controller::ControllerInfo* type : game_types)
      {
        if (type != info)
          types.push_back(type);
      }

      const std::string section = Controller::GetSettingsSection(pad);
      for (const Controller::ControllerInfo* type : types)
      {
        for (const Controller::ControllerBindingInfo& bi : type->bindings)
        {
          // A hotkey (GetHotkeyBindings()).
          if (std::string_view(bi.name) == ANALOG_BUTTON)
            continue;

          PadBindingKind kind;
          switch (bi.type)
          {
            case InputBindingInfo::Type::Button:
              kind = PadBindingKind::Button;
              break;
            case InputBindingInfo::Type::Axis:
              kind = PadBindingKind::Axis;
              break;
            case InputBindingInfo::Type::HalfAxis:
              kind = PadBindingKind::HalfAxis;
              break;
            default:
              continue;
          }

          // One row per [PadN] key: a name another type listed already is that row.
          if (std::any_of(result->begin(), result->end(), [&bi](const PadBinding& b) { return b.name == bi.name; }))
            continue;

          result->push_back(PadBinding{
            bi.name, bi.display_name, kind,
            AndroidInputKeys::GetDeviceBinding(si.GetStringList(section.c_str(), bi.name), index.value()),
            (type == info) ? std::string() : std::string(type->GetDisplayName())});
        }
      }
    },
    true);
  return result;
}

// The macros' triggers' keys, Macro1 to Macro4 (InputManager::NUM_MACRO_BUTTONS_PER_CONTROLLER).
static bool IsMacroKey(std::string_view name)
{
  for (u32 i = 1; i <= InputManager::NUM_MACRO_BUTTONS_PER_CONTROLLER; i++)
  {
    if (name == fmt::format("Macro{}", i))
      return true;
  }

  return false;
}

std::optional<std::vector<AndroidHost::PadBinding>> AndroidHost::GetMacroBindings(std::string descriptor)
{
  if (!s_initialized)
    return std::nullopt;

  std::optional<std::vector<PadBinding>> result;
  EmuThread::RunOnThread(
    [&descriptor, &result]() {
      const std::optional<u32> index = FindDescriptorIndex(descriptor);
      if (!index.has_value())
        return;

      const auto lock = Host::GetSettingsLock();
      SettingsInterface& si = *s_base_settings_interface;
      const std::string section = Controller::GetSettingsSection(GetPadOfIndex(si, index.value()));
      result.emplace();
      for (u32 i = 1; i <= InputManager::NUM_MACRO_BUTTONS_PER_CONTROLLER; i++)
      {
        const std::string key = fmt::format("Macro{}", i);
        result->push_back(PadBinding{key, fmt::format("Macro {}", i), PadBindingKind::Button,
                                     AndroidInputKeys::GetDeviceBinding(si.GetStringList(section.c_str(), key.c_str()),
                                                                        index.value())});
      }
    },
    true);
  return result;
}

std::optional<std::string> AndroidHost::GetPortType(u32 pad)
{
  if (!s_initialized || pad >= AndroidInputKeys::NUM_PADS)
    return std::nullopt;

  // The file's, even one the core doesn't know, so the page can show it as it is.
  const auto lock = Host::GetSettingsLock();
  return s_base_settings_interface->GetStringValue(Controller::GetSettingsSection(pad).c_str(), "Type",
                                                   Controller::GetDefaultPadType(pad));
}

void AndroidHost::SetPortType(u32 pad, std::string type)
{
  if (!s_initialized || pad >= AndroidInputKeys::NUM_PADS)
    return;

  EmuThread::RunOnThread(
    [pad, &type]() {
      {
        const auto lock = Host::GetSettingsLock();
        SettingsInterface& si = *s_base_settings_interface;
        si.SetStringValue(Controller::GetSettingsSection(pad).c_str(), "Type", type.c_str());

        // The bindings are kept for each type, as its inputs' keys: only the ones a controller has none for get theirs.
        for (u32 index = 0; index < s_descriptors.size(); index++)
        {
          if (GetPadOfIndex(si, index) == pad)
            AddDefaultBindings(si, index, pad);
        }

        Error error;
        if (!si.Save(&error))
          ERROR_LOG("Failed to save settings: {}", error.GetDescription());
      }

      System::ApplySettings(false);
    },
    true);
}

std::optional<u32> AndroidHost::GetControllerPort(std::string descriptor)
{
  if (!s_initialized)
    return std::nullopt;

  std::optional<u32> result;
  EmuThread::RunOnThread(
    [&descriptor, &result]() {
      const std::optional<u32> index = FindDescriptorIndex(descriptor);
      if (!index.has_value())
        return;

      const auto lock = Host::GetSettingsLock();
      result = GetPadOfIndex(*s_base_settings_interface, index.value());
    },
    true);
  return result;
}

void AndroidHost::SetControllerPort(std::string descriptor, u32 pad)
{
  if (!s_initialized || pad >= AndroidInputKeys::NUM_PADS)
    return;

  EmuThread::RunOnThread(
    [&descriptor, pad]() {
      const std::optional<u32> index = FindDescriptorIndex(descriptor);
      if (!index.has_value())
        return;

      {
        const auto lock = Host::GetSettingsLock();
        SettingsInterface& si = *s_base_settings_interface;
        const u32 old_pad = GetPadOfIndex(si, index.value());
        if (old_pad == pad)
          return;

        // Every key's entry for this controller, macro triggers too, goes to the same key on the new port. Other
        // sources' and controllers' entries stay where they are, in order.
        const std::string from = Controller::GetSettingsSection(old_pad);
        const std::string to = Controller::GetSettingsSection(pad);
        std::vector<std::string> keys;
        for (const auto& [key, value] : si.GetKeyValueList(from.c_str()))
        {
          if (std::find(keys.begin(), keys.end(), key) == keys.end())
            keys.push_back(key);
        }

        for (const std::string& key : keys)
        {
          const std::vector<std::string> bindings = si.GetStringList(from.c_str(), key.c_str());
          const std::optional<std::string> binding = AndroidInputKeys::GetDeviceBinding(bindings, index.value());
          if (!binding.has_value())
            continue;

          si.SetStringList(from.c_str(), key.c_str(), AndroidInputKeys::SetDeviceBinding(bindings, index.value(), {}));
          si.SetStringList(to.c_str(), key.c_str(),
                           AndroidInputKeys::SetDeviceBinding(si.GetStringList(to.c_str(), key.c_str()), index.value(),
                                                              binding.value()));
        }

        si.SetStringList(DESCRIPTORS_SECTION, PORTS_KEY,
                         AndroidInputKeys::SetPadForIndex(si.GetStringList(DESCRIPTORS_SECTION, PORTS_KEY),
                                                          index.value(), pad));
        const u32 added = AddDefaultBindings(si, index.value(), pad);
        INFO_LOG("Moved {} from Port {} to Port {}, adding {} defaults.", descriptor, old_pad + 1, pad + 1, added);

        Error error;
        if (!si.Save(&error))
          ERROR_LOG("Failed to save settings: {}", error.GetDescription());
      }

      UpdateSourceControllers();
      System::ReloadInputBindings();
    },
    true);
}

std::vector<std::pair<u32, std::string>> AndroidHost::GetOfferedPorts()
{
  if (!s_initialized)
    return {};

  // Multitap's ports are offered when settings.ini or any game's file turns it on for that port.
  std::vector<std::string> modes;
  std::string folder;
  {
    // The CPU thread changes the folders under it (EmuFolders::Update()).
    const auto lock = Host::GetSettingsLock();
    modes.push_back(s_base_settings_interface->GetStringValue("ControllerPorts", "MultitapMode"));
  }

  // Only the games' files whose controller settings count, as the core takes them.
  ForEachControllerGameFile(
    [&modes](SettingsInterface& game) { modes.push_back(game.GetStringValue("ControllerPorts", "MultitapMode")); });

  // By the mode's own name, as Settings::IsPort2MultitapEnabled() reads Port2Only as Port 1's.
  std::array<bool, 2> multitap = {false, false};
  for (const std::string& name : modes)
  {
    const std::optional<MultitapMode> mode = Settings::ParseMultitapModeName(name.c_str());
    if (!mode.has_value())
      continue;

    multitap[0] |= (mode == MultitapMode::Port1Only || mode == MultitapMode::BothPorts);
    multitap[1] |= (mode == MultitapMode::Port2Only || mode == MultitapMode::BothPorts);
  }

  // Desktop's order: a port's slots together, then the other port's.
  static constexpr std::array<u32, AndroidInputKeys::NUM_PADS> ORDER = {0, 2, 3, 4, 1, 5, 6, 7};
  std::vector<std::pair<u32, std::string>> ports;
  for (const u32 pad : ORDER)
  {
    const auto [port, slot] = Controller::ConvertPadToPortAndSlot(pad);
    if (slot == 0 || multitap[port])
      ports.emplace_back(pad, GetPortLabel(pad, multitap));
  }

  return ports;
}

// "Port 1", or "Port 1A" to "Port 1D" for a port with multitap: as a multitap slot always has its letter.
std::string AndroidHost::GetPortLabel(u32 pad, const std::array<bool, 2>& multitap)
{
  const auto [port, slot] = Controller::ConvertPadToPortAndSlot(pad);
  return (multitap[port] || slot != 0) ? fmt::format("Port {}{}", port + 1, static_cast<char>('A' + slot)) :
                                         fmt::format("Port {}", port + 1);
}

// CPU thread, as a game starts: each connected controller whose port this game has no controller on is told so, as it
// drives nothing. The core's own setup is what counts: a port without a type, or a multitap slot with its port's
// multitap off.
void AndroidHost::PostUnusedPortMessages()
{
  const auto in_use = [](u32 pad) {
    if (g_settings.controller_types[pad] == ControllerType::None)
      return false;

    const auto [port, slot] = Controller::ConvertPadToPortAndSlot(pad);
    const MultitapMode mode = g_settings.multitap_mode;
    return slot == 0 || mode == MultitapMode::BothPorts ||
           mode == ((port == 0) ? MultitapMode::Port1Only : MultitapMode::Port2Only);
  };

  std::vector<std::pair<u32, std::string>> offered;
  std::vector<u32> told;
  for (const ConnectedDevice& device : s_connected_devices)
  {
    // Two devices with one descriptor are one controller.
    if (std::find(told.begin(), told.end(), device.index) != told.end())
      continue;
    told.push_back(device.index);

    u32 pad;
    {
      const auto lock = Host::GetSettingsLock();
      pad = GetPadOfIndex(*s_base_settings_interface, device.index);
    }
    if (in_use(pad))
      continue;

    if (offered.empty())
      offered = GetOfferedPorts();
    const auto it = std::find_if(offered.begin(), offered.end(), [pad](const auto& entry) { return entry.first == pad; });
    const std::string label = (it != offered.end()) ? it->second : GetPortLabel(pad, {false, false});
    Host::AddOSDMessage(fmt::format("{} is on {}, which this game doesn't use.", device.name, label),
                        Host::OSD_INFO_DURATION);
  }
}

void AndroidHost::SetListedKeyForTest(std::string path, std::string key)
{
  std::unique_lock lock(s_listed_games_mutex);
  for (AndroidDiscGroups::Game& game : s_listed_games)
  {
    if (game.path == path)
      game.key = key;
  }
}

bool AndroidHost::ReloadPostProcessingShaders()
{
  if (!s_initialized)
    return false;

  bool reloaded = false;
  EmuThread::RunOnThread(
    [&reloaded]() {
      if (System::IsValid())
        reloaded = PostProcessing::ReloadShaders();
    },
    true);
  return reloaded;
}

std::string AndroidHost::GetRunningSettingForTest(std::string section, std::string key)
{
  std::string value;
  EmuThread::RunOnThread(
    [&section, &key, &value]() {
      if (System::IsValid())
        value = Host::GetStringSettingValue(section.c_str(), key.c_str(), "");
    },
    true);
  return value;
}

float AndroidHost::GetPadBindStateForTest(u32 pad, std::string name)
{
  float state = 0.0f;
  EmuThread::RunOnThread(
    [pad, &name, &state]() {
      const Controller* controller = System::IsValid() ? System::GetController(pad) : nullptr;
      const Controller::ControllerInfo* info = controller ? Controller::GetControllerInfo(controller->GetType()) : nullptr;
      if (!info)
        return;

      // By its bind index, which GetBindState() takes: not its place in the list (Controller::GetBindIndex()).
      for (const Controller::ControllerBindingInfo& bi : info->bindings)
      {
        if (name == bi.name)
          state = controller->GetBindState(bi.bind_index);
      }
    },
    true);
  return state;
}

std::optional<std::vector<AndroidHost::PadBinding>> AndroidHost::GetHotkeyBindings(std::string descriptor)
{
  if (!s_initialized)
    return std::nullopt;

  std::optional<std::vector<PadBinding>> result;
  EmuThread::RunOnThread(
    [&descriptor, &result]() {
      const std::optional<u32> index = FindDescriptorIndex(descriptor);
      if (!index.has_value())
        return;

      const auto lock = Host::GetSettingsLock();
      SettingsInterface& si = *s_base_settings_interface;
      result.emplace();
      for (const HotkeyInfo* hotkey : GetOfferedHotkeys(si, GetPadOfIndex(si, index.value())))
      {
        result->push_back(PadBinding{hotkey->name, hotkey->display_name, PadBindingKind::Button,
                                     AndroidInputKeys::GetDeviceBinding(si.GetStringList("Hotkeys", hotkey->name),
                                                                        index.value()),
                                     hotkey->category});
      }
    },
    true);
  return result;
}

void AndroidHost::SetHotkeyBinding(std::string descriptor, std::string name, std::string button)
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread(
    [&descriptor, &name, &button]() {
      const std::optional<u32> index = FindDescriptorIndex(descriptor);
      if (!index.has_value())
        return;

      {
        const auto lock = Host::GetSettingsLock();
        SettingsInterface& si = *s_base_settings_interface;
        const std::vector<const HotkeyInfo*> offered = GetOfferedHotkeys(si, GetPadOfIndex(si, index.value()));
        if (std::none_of(offered.begin(), offered.end(), [&name](const HotkeyInfo* hotkey) { return name == hotkey->name; }))
        {
          WARNING_LOG("'{}' isn't a hotkey this pad can have, not binding it.", name);
          return;
        }

        const std::string binding = button.empty() ? std::string() : AndroidInputKeys::SelectChord(index.value(), button);
        si.SetStringList("Hotkeys", name.c_str(),
                         AndroidInputKeys::SetDeviceBinding(si.GetStringList("Hotkeys", name.c_str()), index.value(),
                                                            binding));

        Error error;
        if (!si.Save(&error))
          ERROR_LOG("Failed to save settings: {}", error.GetDescription());
      }

      System::ReloadInputBindings();
    },
    true);
}

std::optional<u32> AndroidHost::GetControllerIndex(std::string descriptor)
{
  if (!s_initialized)
    return std::nullopt;

  return EmuThread::Call([&descriptor]() { return FindDescriptorIndex(descriptor); });
}

void AndroidHost::SetPadBinding(std::string descriptor, std::string name, std::string binding)
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread(
    [&descriptor, &name, &binding]() {
      const std::optional<u32> index = FindDescriptorIndex(descriptor);
      if (!index.has_value())
        return;

      // An edit made before a new pad's first mapping: its defaults go on first, so it has them and the edit. The
      // mapping, queued meanwhile, then adds nothing (MapNewController()).
      if (std::find(s_unmapped_indices.begin(), s_unmapped_indices.end(), index.value()) != s_unmapped_indices.end() &&
          AddControllerToPort(fmt::format("{}{}", DEVICE_PREFIX, index.value())))
      {
        std::erase(s_unmapped_indices, index.value());
      }

      u32 game_pad;
      {
        const auto lock = Host::GetSettingsLock();
        game_pad = GetPadOfIndex(*s_base_settings_interface, index.value());
      }
      const std::vector<const Controller::ControllerInfo*> game_types = GetGameTypes(game_pad);

      {
        const auto lock = Host::GetSettingsLock();
        SettingsInterface& si = *s_base_settings_interface;
        // Only what GetPadBindings() and GetMacroBindings() list: a button, an axis or a macro's trigger, never a
        // pointer or a motor. The port's type's, or a type a game's own file gives it.
        const u32 pad = GetPadOfIndex(si, index.value());
        const Controller::ControllerInfo* info = GetPadControllerInfo(si, pad);
        const auto has_input = [&name](const Controller::ControllerInfo* type) {
          return type && std::any_of(type->bindings.begin(), type->bindings.end(),
                                     [&name](const Controller::ControllerBindingInfo& bi) {
                                       return name == bi.name && IsPadBindingType(bi.type);
                                     });
        };
        if (!info || (!IsMacroKey(name) && !has_input(info) &&
                      (pad != game_pad || std::none_of(game_types.begin(), game_types.end(), has_input))))
        {
          WARNING_LOG("Port {} has no button or axis '{}', not binding it.", pad + 1, name);
          return;
        }

        // Its defaults went on above if they could; if not (it's gone again), none come over the edit later.
        std::erase(s_unmapped_indices, index.value());

        const std::string section = Controller::GetSettingsSection(pad);
        si.SetStringList(section.c_str(), name.c_str(),
                         AndroidInputKeys::SetDeviceBinding(si.GetStringList(section.c_str(), name.c_str()),
                                                            index.value(), binding));

        Error error;
        if (!si.Save(&error))
          ERROR_LOG("Failed to save settings: {}", error.GetDescription());
      }

      System::ReloadInputBindings();
    },
    true);
}

// Whether the pad's controller type has the Analog button (a DualShock), under the settings lock.
bool AndroidHost::PadHasAnalogButton(SettingsInterface& si, u32 pad)
{
  const Controller::ControllerInfo* info = GetPadControllerInfo(si, pad);
  return info && std::any_of(info->bindings.begin(), info->bindings.end(),
                             [](const Controller::ControllerBindingInfo& bi) { return bi.name == ANALOG_BUTTON; });
}

// The hotkeys the Controllers page offers for a controller on pad, in the core's order, under the settings lock.
std::vector<const HotkeyInfo*> AndroidHost::GetOfferedHotkeys(SettingsInterface& si, u32 pad)
{
  const bool analog = PadHasAnalogButton(si, pad);
  std::vector<const HotkeyInfo*> offered;
  for (const HotkeyInfo* hotkey : InputManager::GetHotkeyList())
  {
    const std::string_view name(hotkey->name);
    if (std::find(std::begin(LEFT_OUT_HOTKEYS), std::end(LEFT_OUT_HOTKEYS), name) != std::end(LEFT_OUT_HOTKEYS) ||
        (name == ANALOG_HOTKEY && !analog))
    {
      continue;
    }

    offered.push_back(hotkey);
  }

  return offered;
}

bool AndroidHost::UndoLoadState()
{
  if (!s_initialized)
    return false;

  // An undo is a load, which hardcore mode allows none of; the core doesn't refuse it.
  bool undone = false;
  EmuThread::RunOnThread(
    [&undone]() {
      undone = System::IsValid() && !Achievements::IsHardcoreModeActive() && System::UndoLoadState();
    },
    true);
  return undone;
}

bool AndroidHost::CanUndoLoadState()
{
  if (!s_initialized)
    return false;

  return EmuThread::Call(false, []() {
    return System::IsValid() && !Achievements::IsHardcoreModeActive() && System::CanUndoLoadState();
  });
}

// Moves [Pad1] Analog's Android bindings (the old Analog Toggle row's) to the analog mode hotkey, under the settings
// lock. Returns whether it changed anything, to save.
bool AndroidHost::MoveAnalogBindings(SettingsInterface& si)
{
  const std::string section = Controller::GetSettingsSection(0);
  const std::string hotkey(ANALOG_HOTKEY);
  const AndroidInputKeys::AnalogBindings moved = AndroidInputKeys::MoveAnalogBindings(
    si.GetStringList(section.c_str(), "Analog"), si.GetStringList("Hotkeys", hotkey.c_str()));
  if (!moved.changed)
    return false;

  INFO_LOG("Moved the Analog Toggle bindings to the {} hotkey.", hotkey);
  si.SetStringList(section.c_str(), "Analog", moved.analog);
  si.SetStringList("Hotkeys", hotkey.c_str(), moved.hotkey);
  return true;
}

// CPU thread: presses the Analog button of the port whose controller fired the chord, as the DualShock's own does, if
// it's a DualShock.
void AndroidHost::PressAnalogButton()
{
  AndroidInputSource* const source =
    static_cast<AndroidInputSource*>(InputManager::GetInputSourceInterface(InputSourceType::Android));
  const std::optional<u32> index = source ? source->GetLastUsedIndex() : std::nullopt;
  u32 pad = 0;
  if (index.has_value())
  {
    const auto lock = Host::GetSettingsLock();
    pad = GetPadOfIndex(*s_base_settings_interface, index.value());
  }

  Controller* controller = System::IsValid() ? System::GetController(pad) : nullptr;
  if (!controller || controller->GetType() != ControllerType::AnalogController)
    return;

  // The press toggles, the release does nothing.
  const u32 analog = static_cast<u32>(AnalogController::Button::Analog);
  controller->SetBindState(analog, 1.0f);
  controller->SetBindState(analog, 0.0f);
}

void AndroidHost::ResetPadBindings(std::string descriptor)
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread(
    [&descriptor]() {
      const std::optional<u32> index = FindDescriptorIndex(descriptor);
      if (!index.has_value())
        return;

      u32 game_pad;
      {
        const auto lock = Host::GetSettingsLock();
        game_pad = GetPadOfIndex(*s_base_settings_interface, index.value());
      }
      const std::vector<const Controller::ControllerInfo*> game_types = GetGameTypes(game_pad);

      {
        const auto lock = Host::GetSettingsLock();
        SettingsInterface& si = *s_base_settings_interface;
        const u32 pad = GetPadOfIndex(si, index.value());
        const std::string section = Controller::GetSettingsSection(pad);

        // Its defaults go on now; the ones a queued first mapping would add are already these.
        std::erase(s_unmapped_indices, index.value());

        // From every key, not only the controller type's: another type's, or a macro's, would come back with them.
        std::vector<std::string> keys;
        for (const auto& [key, value] : si.GetKeyValueList(section.c_str()))
        {
          if (std::find(keys.begin(), keys.end(), key) == keys.end())
            keys.push_back(key);
        }

        for (const std::string& key : keys)
        {
          const std::vector<std::string> bindings = si.GetStringList(section.c_str(), key.c_str());
          const std::vector<std::string> kept = AndroidInputKeys::SetDeviceBinding(bindings, index.value(), {});
          if (kept.size() != bindings.size())
            si.SetStringList(section.c_str(), key.c_str(), kept);
        }

        // Its hotkeys too, every one the core has: unassigned by default.
        for (const HotkeyInfo* hotkey : InputManager::GetHotkeyList())
        {
          const std::vector<std::string> bindings = si.GetStringList("Hotkeys", hotkey->name);
          const std::vector<std::string> kept = AndroidInputKeys::SetDeviceBinding(bindings, index.value(), {});
          if (kept.size() != bindings.size())
            si.SetStringList("Hotkeys", hotkey->name, kept);
        }

        AddDefaultBindings(si, index.value(), pad);
        // And those of the types games' own files give the port.
        if (pad == game_pad)
        {
          for (const Controller::ControllerInfo* type : game_types)
            AddDefaultBindings(si, index.value(), pad, type);
        }

        Error error;
        if (!si.Save(&error))
          ERROR_LOG("Failed to save settings: {}", error.GetDescription());
      }

      System::ReloadInputBindings();
    },
    true);
}

void AndroidHost::StartBindingCapture(std::string descriptor, bool select_chord)
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread(
    [&descriptor, select_chord]() {
      const std::optional<u32> index = FindDescriptorIndex(descriptor);
      if (!index.has_value())
        return;

      // SetHook() expects no hook, so the capture running is removed first.
      InputManager::RemoveHook();
      const u32 id = ++s_binding_capture_id;

      // Seeded with the axes the pad already has at rest, so a quick flick of a centred stick is taken on its first
      // event. The input sent before this call (the app sends the pad's current axes just before it) is passed on
      // first: this thread runs its queued work before it polls the input. So this must never run inline during
      // event dispatch (from a hook or an input handler): the nested PollEvents() would swap the source's event list
      // while it's being gone through.
      AndroidInputSource* const source =
        static_cast<AndroidInputSource*>(InputManager::GetInputSourceInterface(InputSourceType::Android));
      ProcessInputQueue();
      if (source)
        source->PollEvents();
      AndroidInputKeys::BindingCapture capture(
        index.value(), source ? source->GetCenteredAxes(index.value()) : std::vector<u32>(), select_chord);
      InputManager::SetHook([capture, id](InputBindingKey key, float value) mutable {
        const std::optional<InputBindingKey> captured = capture.OnEvent(key, value);
        if (!captured.has_value())
          return InputInterceptHook::CallbackResult::StopProcessingEvent;

        // HalfAxis for an axis, so the binding has its direction (+Axis23, -Axis0).
        std::string binding = InputManager::ConvertInputBindingKeyToString(
          (captured->source_subtype == InputSubclass::ControllerAxis) ? InputBindingInfo::Type::HalfAxis :
                                                                        InputBindingInfo::Type::Button,
          captured.value());
        const std::string::size_type slash = binding.find('/');
        if (slash != std::string::npos)
          binding.erase(0, slash + 1);

        // Reported once the event is dispatched: the InputManager holds its hook lock while this runs, so an app
        // callback that started or cancelled a capture straight away, on this thread, would deadlock. A capture
        // cancelled or replaced before then isn't reported.
        EmuThread::RunOnThread(
          [binding = std::move(binding), id]() {
            if (id == s_binding_capture_id)
              NotifyBindingCaptured(binding);
          },
          false);
        return InputInterceptHook::CallbackResult::RemoveHookAndStopProcessingEvent;
      });
    },
    true);
}

void AndroidHost::CancelBindingCapture()
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread(
    []() {
      s_binding_capture_id++;
      InputManager::RemoveHook();
    },
    true);
}
