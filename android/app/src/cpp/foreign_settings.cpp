// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "foreign_settings.h"

#include "core/settings.h"

#include "util/input_manager.h"

#include "common/log.h"
#include "common/path.h"
#include "common/settings_interface.h"

#include <string>

Log_SetChannel(AndroidHost);

namespace AndroidHost {
namespace {
struct FolderKey
{
  const char* section;
  const char* key;
  const char* default_value;
};

// The folders EmuFolders::LoadConfig() reads, with its defaults, except the BIOS one, which is always replaced.
constexpr FolderKey FOLDER_KEYS[] = {
  {"Folders", "Cache", "cache"},
  {"Folders", "Cheats", "cheats"},
  {"Folders", "Covers", "covers"},
  {"Folders", "Dumps", "dump"},
  {"Folders", "GameIcons", "gameicons"},
  {"Folders", "GameSettings", "gamesettings"},
  {"Folders", "InputProfiles", "inputprofiles"},
  {"MemoryCards", "Directory", "memcards"},
  {"Folders", "SaveStates", "savestates"},
  {"Folders", "Screenshots", "screenshots"},
  {"Folders", "Shaders", "shaders"},
  {"Folders", "Textures", "textures"},
  {"Folders", "UserResources", "resources"},
  {"Folders", "Videos", "videos"},
};

constexpr const char* MEMORY_CARD_PATH_KEYS[] = {"Card1Path", "Card2Path"};
constexpr const char* BIOS_IMAGE_KEYS[] = {"PathNTSCU", "PathNTSCJ", "PathPAL"};

// A path from a Windows install: with a drive letter (C:\x, D:/x), a UNC one (\\server\x) or any other with backslashes
// (..\..\x). Here it would be one file name holding backslashes, so it's never inside the data folder.
bool IsWindowsPath(std::string_view path)
{
  const bool drive_letter =
    path.size() >= 2 && ((path[0] >= 'A' && path[0] <= 'Z') || (path[0] >= 'a' && path[0] <= 'z')) && path[1] == ':';
  return drive_letter || path.find('\\') != std::string_view::npos;
}

bool IsInsideDataRoot(std::string_view path)
{
  return AndroidHost::RelativeToDataRoot(path).has_value();
}
} // namespace
} // namespace AndroidHost

std::optional<std::string> AndroidHost::RelativeToDataRoot(std::string_view path)
{
  if (IsWindowsPath(path))
    return std::nullopt;

  // Both sides resolved alike: the core's folders are real paths, and the app's files folder is under a symbolic link
  // (/data/user/0 to /data/data).
  const std::string full = Path::RealPath(
    Path::Canonicalize(Path::IsAbsolute(path) ? std::string(path) : Path::Combine(EmuFolders::DataRoot, path)));
  const std::string root = Path::RealPath(EmuFolders::DataRoot);
  if (full == root)
    return std::string();
  if (!full.starts_with(root) || full.size() <= root.size() || full[root.size()] != '/')
    return std::nullopt;
  return full.substr(root.size() + 1);
}

bool AndroidHost::AdaptForeignSettings(SettingsInterface& si, std::string_view install_id)
{
  const std::string file_install_id = si.GetStringValue("Android", "InstallID");
  if (file_install_id == install_id)
    return false;

  INFO_LOG("The settings are from another install ('{}'), adapting them.", file_install_id);

  si.DeleteValue("GPU", "Renderer");

  si.DeleteValue("GameList", "Paths");
  si.DeleteValue("GameList", "RecursivePaths");

  for (const char* key : BIOS_IMAGE_KEYS)
    si.DeleteValue("BIOS", key);
  si.SetStringValue("BIOS", "SearchDirectory", "bios");

  InputManager::SetDefaultSourceConfig(si);
  Settings::SetDefaultControllerConfig(si);
  Settings::SetDefaultHotkeyConfig(si);

  for (const FolderKey& folder : FOLDER_KEYS)
  {
    const std::string value = si.GetStringValue(folder.section, folder.key);
    if (!value.empty() && !IsInsideDataRoot(value))
    {
      INFO_LOG("[{}] {} '{}' is outside the data folder, using '{}'.", folder.section, folder.key, value,
               folder.default_value);
      si.SetStringValue(folder.section, folder.key, folder.default_value);
    }
  }

  // Relative card paths are inside the memory card folder, as Settings::GetSharedMemoryCardPath() has them, and can
  // still leave the data folder with "..".
  std::string memcards = si.GetStringValue("MemoryCards", "Directory", "memcards");
  if (memcards.empty())
    memcards = "memcards";
  for (const char* key : MEMORY_CARD_PATH_KEYS)
  {
    const std::string value = si.GetStringValue("MemoryCards", key);
    if (value.empty())
      continue;

    const std::string card = (Path::IsAbsolute(value) || IsWindowsPath(value)) ? value : Path::Combine(memcards, value);
    if (!IsInsideDataRoot(card))
    {
      INFO_LOG("[MemoryCards] {} '{}' is outside the data folder, using the default.", key, value);
      si.DeleteValue("MemoryCards", key);
    }
  }

  si.SetStringValue("Android", "InstallID", std::string(install_id).c_str());
  return true;
}
