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

static bool InitializeConfig(Error* error);
static void SetDefaultSettings(SettingsInterface& si);
static void LoadFolders(SettingsInterface& si);

// Plan 1's Android settings.ini: the same keys as now, so it's upgraded in place.
static constexpr u32 PLAN1_SETTINGS_VERSION = 1;

static std::mutex s_initialize_mutex;

static bool s_startup_attempted = false; // guarded by s_initialize_mutex

/// The secrets layer, in memory only: the RetroAchievements login token comes from the app's Keystore-sealed store
/// at startup, and each change the core saves goes back to the app to store (NotifyAchievementsToken). No secrets.ini
/// is read or written, so the token never reaches the data folder.
class AndroidSecrets final : public MemorySettingsInterface
{
public:
  explicit AndroidSecrets(std::string token) : m_reported(std::move(token))
  {
    if (!m_reported.empty())
      SetStringValue(SECTION, KEY, m_reported.c_str());
  }

  bool Save(Error* error = nullptr) override
  {
    std::string token;
    GetStringValue(SECTION, KEY, &token);
    if (token != m_reported)
    {
      m_reported = token;
      NotifyAchievementsToken(token.empty() ? std::nullopt : std::optional<std::string_view>(token));
    }

    return true;
  }

private:
  static constexpr const char* SECTION = "Cheevos";
  static constexpr const char* KEY = "Token";

  std::string m_reported;
};

static std::string s_initial_achievements_token; // until InitializeConfig() makes the secrets layer
static std::unique_ptr<AndroidSecrets> s_secrets_settings_interface;

// GameList::Refresh() isn't safe to run twice at once.
static std::mutex s_game_list_refresh_mutex;

} // namespace AndroidHost

bool Host::StoreMemcardSyncSecret(const char* name, std::string_view value)
{
  return AndroidHost::StoreMemcardSyncSecret(name, value.empty() ? std::nullopt : std::optional(value));
}

std::optional<std::string> Host::LoadMemcardSyncSecret(const char* name)
{
  return AndroidHost::LoadMemcardSyncSecret(name);
}

bool Host::HasMemcardSyncSecret(const char* name)
{
  return AndroidHost::HasMemcardSyncSecret(name);
}

std::string Host::GetMemcardSyncDefaultDeviceName()
{
  return AndroidHost::s_device_name;
}

void Host::OnMemcardSyncChanged()
{
  AndroidHost::NotifyMemcardSyncChanged();
}

void AndroidHost::SetShaderPacksFolder(std::string path)
{
  EmuFolders::ShaderPacks = Path::Canonicalize(path);
  Error error;
  if (!FileSystem::EnsureDirectoryExists(EmuFolders::ShaderPacks.c_str(), true, &error))
    WARNING_LOG("Couldn't create the shader packs folder {}: {}", EmuFolders::ShaderPacks, error.GetDescription());
  INFO_LOG("Shader packs Directory: {}", EmuFolders::ShaderPacks);
}

bool AndroidHost::Initialize(std::string data_root, std::string resources_root, std::string bios_dir,
                             std::string install_id, std::string achievements_token, std::string achievements_folder,
                             std::string device_name, Error* error)
{
  std::unique_lock lock(s_initialize_mutex);
  if (s_initialized)
    return true;

  // The core's messages go to logcat.
  Log::SetDebugOutputParams(true);

  EmuFolders::AppRoot = Path::Canonicalize(resources_root);
  EmuFolders::Resources = EmuFolders::AppRoot;
  EmuFolders::DataRoot = Path::Canonicalize(data_root);
  // The last run's log, kept before the settings' Log to file rewrites it, so a crash's log survives the restart.
  if (const std::string log = Path::Combine(EmuFolders::DataRoot, LOG_FILE); FileSystem::FileExists(log.c_str()))
  {
    Error rename_error;
    if (!FileSystem::RenamePath(log.c_str(), Path::Combine(EmuFolders::DataRoot, PREVIOUS_LOG_FILE).c_str(),
                                &rename_error))
    {
      WARNING_LOG("Couldn't keep the last run's log: {}", rename_error.GetDescription());
    }
  }
  s_bios_dir = Path::Canonicalize(bios_dir);
  s_install_id = std::move(install_id);
  s_device_name = std::move(device_name);
  s_initial_achievements_token = std::move(achievements_token);
  INFO_LOG("DataRoot Directory: {}", EmuFolders::DataRoot);
  INFO_LOG("Resources Directory: {}", EmuFolders::Resources);
  INFO_LOG("BIOS Directory: {}", s_bios_dir);

  if (!FileSystem::DirectoryExists(EmuFolders::Resources.c_str()))
  {
    Error::SetStringFmt(error, "The resources directory '{}' is missing.", EmuFolders::Resources);
    return false;
  }

  if (!FileSystem::EnsureDirectoryExists(EmuFolders::DataRoot.c_str(), false, error))
  {
    Error::AddPrefixFmt(error, "Failed to create the data directory '{}': ", EmuFolders::DataRoot);
    return false;
  }

  // The settings layers can only be set once, and the core's startup only run once: after a failure from here on, a
  // later call can't retry.
  if (s_startup_attempted)
  {
    Error::SetStringView(error, "Initialization failed earlier; restart the app.");
    return false;
  }
  s_startup_attempted = true;

  // Unlocks and entries waiting for the server, and what a game needs to start offline: in the app's own storage,
  // never in the data root, which is mirrored.
  AchievementsOffline::Enable(Path::Canonicalize(achievements_folder),
                              AchievementsOffline::Callbacks{
                                .changed = []() { Host::OnAchievementsRefreshed(); },
                                .refused = [](std::string_view title) { NotifyAchievementRefused(title); },
                                .sent = [](u32 count) { NotifyAchievementsSent(count); },
                              });

  if (!InitializeConfig(error))
    return false;

  // GameDatabase loads itself on first use, unlocked: loaded here, before the CPU thread (a boot) or a game list
  // refresh can use it, every later use only reads it.
  GameDatabase::EnsureLoaded();

  if (!EmuThread::Start(error))
    return false;

  {
    std::unique_lock input_lock(s_input_mutex);
    s_accept_input_events = true;
  }

  s_initialized = true;
  return true;
}

bool AndroidHost::InitializeConfig(Error* error)
{
  // As the Qt frontend's QtHost::InitializeConfig().
  std::string settings_filename = Path::Combine(EmuFolders::DataRoot, "settings.ini");
  const bool settings_exists = FileSystem::FileExists(settings_filename.c_str());
  INFO_LOG("Loading config from {}.", settings_filename);
  s_base_settings_interface = std::make_unique<INISettingsInterface>(std::move(settings_filename));
  Host::Internal::SetBaseSettingsLayer(s_base_settings_interface.get());

  const auto lock = Host::GetSettingsLock();
  INISettingsInterface& si = *s_base_settings_interface;
  bool changed = false;
  u32 settings_version;
  if (!settings_exists || !si.Load() || !si.GetUIntValue("Main", "SettingsVersion", &settings_version) ||
      (settings_version != Settings::SETTINGS_VERSION && settings_version != PLAN1_SETTINGS_VERSION))
  {
    if (si.ContainsValue("Main", "SettingsVersion"))
    {
      WARNING_LOG("Settings version {} does not match expected version {}, resetting.", settings_version,
                  Settings::SETTINGS_VERSION);
    }

    si.SetUIntValue("Main", "SettingsVersion", Settings::SETTINGS_VERSION);
    SetDefaultSettings(si);
    changed = true;
  }
  else if (settings_version == PLAN1_SETTINGS_VERSION)
  {
    INFO_LOG("Upgrading the settings from version {} to {}.", settings_version, Settings::SETTINGS_VERSION);
    si.SetUIntValue("Main", "SettingsVersion", Settings::SETTINGS_VERSION);
    changed = true;
  }

  // Defaults too: a file reset from another version keeps the keys the defaults don't have, such as the game folders.
  changed = AdaptForeignSettings(si, s_install_id, s_device_name) || changed;
  changed = MoveAnalogBindings(si) || changed;

  // The controllers this install has seen, at the indices their bindings use. An adapted file has none.
  s_descriptors = si.GetStringList(DESCRIPTORS_SECTION, DESCRIPTORS_KEY);

  // Saving the defaults also makes sure the config can be saved at all.
  if (changed && !si.Save(error))
  {
    Error::AddPrefixFmt(error, "Failed to save configuration to '{}': ", si.GetFileName());
    return false;
  }

  // Secrets stay in memory (AndroidSecrets): the app keeps the token, sealed, outside the data folder.
  s_secrets_settings_interface = std::make_unique<AndroidSecrets>(std::move(s_initial_achievements_token));
  s_initial_achievements_token = {};
  Host::Internal::SetSecretsSettingsLayer(s_secrets_settings_interface.get());

  LoadFolders(si);
  EmuFolders::EnsureFoldersExist();
  return true;
}

void AndroidHost::SetDefaultSettings(SettingsInterface& si)
{
  System::SetDefaultSettings(si);
  EmuFolders::SetDefaults();
  EmuFolders::Save(si);

  InputManager::SetDefaultSourceConfig(si);
  Settings::SetDefaultControllerConfig(si);
  Settings::SetDefaultHotkeyConfig(si);

  // The bindings are reset, so the controller indices they were for go too: every controller is new again.
  si.DeleteValue(DESCRIPTORS_SECTION, DESCRIPTORS_KEY);
}

void AndroidHost::LoadFolders(SettingsInterface& si)
{
  // The BIOS images are the app's own copies, outside the data folder: [BIOS] SearchDirectory stays as the file has
  // it, for the desktop that shares it.
  EmuFolders::LoadConfig(si);
  EmuFolders::Bios = s_bios_dir;
}

void AndroidHost::SetGameFolders(std::vector<std::string> tree_uris)
{
  if (!s_initialized)
    return;

  const auto lock = Host::GetSettingsLock();
  INISettingsInterface& si = *s_base_settings_interface;
  si.DeleteValue("GameList", "Paths");
  si.SetStringList("GameList", "RecursivePaths", tree_uris);

  Error error;
  if (!si.Save(&error))
    ERROR_LOG("Failed to save settings: {}", error.GetDescription());
}

std::optional<std::string> AndroidHost::GetBIOSDirectory()
{
  if (!s_initialized)
    return std::nullopt;

  return s_bios_dir;
}

void AndroidHost::RefreshGameList(bool invalidate_cache)
{
  if (!s_initialized)
    return;

  std::unique_lock lock(s_game_list_refresh_mutex);
  GameList::Refresh(invalidate_cache, false, nullptr);
}
