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

} // namespace AndroidHost

// CPU thread, as a game starts: each connected controller gets the default bindings of its port's type in effect
// (from the game's own file, maybe from desktop) where it has none, so it's never left unbound.
void AndroidHost::AddRunningTypesDefaults()
{
  u32 added = 0;
  {
    const auto lock = Host::GetSettingsLock();
    SettingsInterface& si = *s_base_settings_interface;
    for (const ConnectedDevice& device : s_connected_devices)
    {
      const u32 pad = GetPadOfIndex(si, device.index);
      added += AddDefaultBindings(si, device.index, pad, Controller::GetControllerInfo(g_settings.controller_types[pad]));
    }

    Error error;
    if (added > 0 && !si.Save(&error))
      ERROR_LOG("Failed to save settings: {}", error.GetDescription());
  }

  if (added > 0)
  {
    INFO_LOG("Added {} bindings for the game's controller types.", added);
    System::ReloadInputBindings();
  }
}

std::optional<std::vector<std::string>> AndroidHost::GetMemoryCardPaths()
{
  if (!s_initialized)
    return std::nullopt;

  // A running game's cards, which the CPU thread owns: the files it has open, and the shared card paths its settings
  // give (a gamesettings/<serial>.ini can override settings.ini's), resolved as the core does.
  std::vector<std::string> game_cards;
  EmuThread::RunOnThread(
    [&game_cards]() {
      if (!System::IsValid())
        return;

      for (u32 slot = 0; slot < NUM_CONTROLLER_AND_CARD_PORTS; slot++)
      {
        if (const MemoryCard* card = Pad::GetMemoryCard(slot); card && !card->GetFilename().empty())
          game_cards.push_back(card->GetFilename());
        if (!g_settings.memory_card_paths[slot].empty())
          game_cards.push_back(g_settings.GetSharedMemoryCardPath(slot));
      }
    },
    true);

  // EmuFolders::Update() changes the folders under the lock.
  std::string memory_cards_dir;
  std::string game_settings_dir;
  std::vector<std::string> cards;
  {
    const auto lock = Host::GetSettingsLock();
    memory_cards_dir = EmuFolders::MemoryCards;
    game_settings_dir = EmuFolders::GameSettings;
    for (const char* key : {"Card1Path", "Card2Path"})
      cards.push_back(s_base_settings_interface->GetStringValue("MemoryCards", key));
  }

  // Every game's own settings, which System::LoadSettings() layers over settings.ini when it runs: read before any
  // game does, so the app holds its cards from the start.
  FileSystem::FindResultsArray game_settings;
  FileSystem::FindFiles(game_settings_dir.c_str(), "*.ini", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_HIDDEN_FILES,
                        &game_settings);
  std::sort(game_settings.begin(), game_settings.end(),
            [](const FILESYSTEM_FIND_DATA& a, const FILESYSTEM_FIND_DATA& b) { return a.FileName < b.FileName; });
  for (const FILESYSTEM_FIND_DATA& file : game_settings)
  {
    INISettingsInterface si(file.FileName);
    Error error;
    if (!si.Load(&error))
    {
      WARNING_LOG("Can't read {}: {}", file.FileName, error.GetDescription());
      continue;
    }
    for (const char* key : {"Card1Path", "Card2Path"})
      cards.push_back(si.GetStringValue("MemoryCards", key));
  }

  std::vector<std::string> paths;
  const std::optional<std::string> folder = RelativeToDataRoot(memory_cards_dir);
  if (folder.has_value())
    paths.push_back(folder->empty() ? std::string() : *folder + '/');

  // As Settings::GetSharedMemoryCardPath() resolves them, then the running game's, which are full paths already.
  std::vector<std::string> files;
  for (const std::string& value : cards)
  {
    if (!value.empty())
      files.push_back(Path::IsAbsolute(value) ? value : Path::Combine(memory_cards_dir, value));
  }
  files.insert(files.end(), game_cards.begin(), game_cards.end());

  // Unless the memory card folder holds them already, or they're listed.
  for (const std::string& card : files)
  {
    const std::optional<std::string> relative = RelativeToDataRoot(card);
    if (!relative.has_value() || relative->empty())
      continue;
    // The data root as the folder holds only its top-level files, as the mirror sees it.
    const bool in_folder = folder.has_value() && (folder->empty() ? relative->find('/') == std::string::npos :
                                                                    relative->starts_with(paths.front()));
    if (!in_folder && std::find(paths.begin(), paths.end(), *relative) == paths.end())
      paths.push_back(*relative);
  }
  return paths;
}

std::optional<std::vector<std::string>> AndroidHost::GetDataFolders()
{
  if (!s_initialized)
    return std::nullopt;

  const auto lock = Host::GetSettingsLock();
  std::vector<std::string> folders;
  for (const std::string* folder :
       {&EmuFolders::Bios, &EmuFolders::Cheats, &EmuFolders::Covers, &EmuFolders::Dumps, &EmuFolders::GameIcons,
        &EmuFolders::GameSettings, &EmuFolders::InputProfiles, &EmuFolders::MemoryCards, &EmuFolders::PostProcessing,
        &EmuFolders::SaveStates, &EmuFolders::Screenshots, &EmuFolders::Shaders, &EmuFolders::Textures,
        &EmuFolders::UserResources, &EmuFolders::Videos})
  {
    const std::optional<std::string> relative = RelativeToDataRoot(*folder);
    if (relative.has_value())
      folders.push_back(relative->empty() ? std::string() : *relative + '/');
  }
  return folders;
}

std::optional<std::vector<std::string>> AndroidHost::GetDataFiles()
{
  if (!s_initialized)
    return std::nullopt;

  const auto lock = Host::GetSettingsLock();
  if (!s_base_settings_interface->GetBoolValue("Logging", "LogToFile", false))
    return std::vector<std::string>();
  return std::vector<std::string>{LOG_FILE, PREVIOUS_LOG_FILE};
}

std::optional<std::string> AndroidHost::GetCacheFolder()
{
  if (!s_initialized)
    return std::nullopt;

  const auto lock = Host::GetSettingsLock();
  const std::optional<std::string> relative = RelativeToDataRoot(EmuFolders::Cache);
  if (!relative.has_value() || relative->empty())
    return std::nullopt;
  return *relative + '/';
}

std::optional<std::string> AndroidHost::GetShadersFolder()
{
  if (!s_initialized)
    return std::nullopt;

  const auto lock = Host::GetSettingsLock();
  const std::optional<std::string> relative = RelativeToDataRoot(EmuFolders::Shaders);
  if (!relative.has_value() || relative->empty())
    return std::nullopt;
  return relative;
}

// What a load or resume says when the disc a state was saved on isn't in the library.
std::string MissingDiscMessage(std::string_view media_path)
{
  const size_t separator = media_path.find_last_of("/\\");
  const std::string_view name = (separator == std::string_view::npos) ? media_path : media_path.substr(separator + 1);
  return fmt::format("The disc this state was saved on isn't in your library: {}.", name);
}
