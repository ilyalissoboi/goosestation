// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_host.h"
#include "emu_thread.h"
#include "foreign_settings.h"

#include "core/achievements.h"
#include "core/controller.h"
#include "core/fullscreen_ui.h"
#include "core/game_database.h"
#include "core/game_list.h"
#include "core/host.h"
#include "core/memory_card.h"
#include "core/pad.h"
#include "core/settings.h"
#include "core/system.h"

#include "util/android_input_keys.h"
#include "util/android_input_source.h"
#include "util/gpu_device.h"
#include "util/imgui_fullscreen.h"
#include "util/imgui_manager.h"
#include "util/ini_settings_interface.h"
#include "util/input_manager.h"
#include "util/window_info.h"

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
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <optional>
#include <utility>
#include <vector>

Log_SetChannel(AndroidHost);

namespace AndroidHost {
namespace {
struct InputEvent
{
  enum class Type : u8
  {
    Key,
    Axis,
    Connected,
    Disconnected,
    ReleaseAll,
  };

  Type type;
  s32 device_id;
  s32 code;
  float value;
  std::string name;
  std::string descriptor;
};

struct ConnectedDevice
{
  s32 device_id;
  u32 index;
  std::string name;
};
} // namespace

static bool InitializeConfig(Error* error);
static void SetDefaultSettings(SettingsInterface& si);
static void LoadFolders(SettingsInterface& si);
static void QueueInputEvent(InputEvent event);
static void SetWindow(ANativeWindow* window, s32 width, s32 height, float scale);
static std::optional<u32> FindDescriptorIndex(std::string_view descriptor);
static void WriteDescriptors(SettingsInterface& si);
static const Controller::ControllerInfo* GetPort1ControllerInfo(SettingsInterface& si);
static u32 AddDefaultBindings(SettingsInterface& si, u32 index);
static bool HasDeviceBinding(SettingsInterface& si, u32 index);
static bool AddControllerToPort1(const std::string& identifier);
static void MapNewController(const std::string& identifier);
static bool IsVulkanRenderer(GPURenderer renderer);

// Plan 1's Android settings.ini: the same keys as now, so it's upgraded in place.
static constexpr u32 PLAN1_SETTINGS_VERSION = 1;

using AndroidInputKeys::DEVICE_PREFIX;
using AndroidInputKeys::ParseDeviceIndex;

// Where each controller's device index (Android-<index>) is kept, by its descriptor.
static constexpr const char* DESCRIPTORS_SECTION = AndroidInputKeys::SETTINGS_SECTION;
static constexpr const char* DESCRIPTORS_KEY = AndroidInputKeys::DESCRIPTORS_KEY;

// The inputs the remap page lists (GetPadBindings()) and edits (SetPadBinding()).
static constexpr bool IsPadBindingType(InputBindingInfo::Type type)
{
  return type == InputBindingInfo::Type::Button || type == InputBindingInfo::Type::Axis ||
         type == InputBindingInfo::Type::HalfAxis;
}

static std::mutex s_initialize_mutex;
static std::atomic_bool s_initialized{false};
static bool s_startup_attempted = false; // guarded by s_initialize_mutex
static std::unique_ptr<INISettingsInterface> s_base_settings_interface;
static std::unique_ptr<INISettingsInterface> s_secrets_settings_interface;

// GameList::Refresh() isn't safe to run twice at once.
static std::mutex s_game_list_refresh_mutex;

// Set once by Initialize(), before s_initialized.
static std::string s_bios_dir;
static std::string s_install_id;

// Input queued by the app's threads.
static std::mutex s_input_mutex;
static std::vector<InputEvent> s_input_queue;
static std::vector<s32> s_queued_device_ids; // connected as far as the queue goes, for QueueKeyEvent()'s result
static bool s_accept_input_events = false;   // key and axis events are dropped until the CPU thread runs

// Each controller's descriptor, at its device index: [Android] ControllerDescriptors. It's only ever appended to, so a
// controller's index never changes in this process, and its bindings stay its own. InitializeConfig() seeds it before
// the CPU thread starts; only the CPU thread touches it after that.
static std::vector<std::string> s_descriptors;

// CPU thread only.
static std::vector<InputEvent> s_input_events;
static std::vector<ConnectedDevice> s_connected_devices;
static std::vector<u32> s_unmapped_indices; // new descriptors' indices, whose defaults aren't on Port 1 yet
static AndroidInputSource* s_input_source = nullptr;
static ANativeWindow* s_window = nullptr;
static u32 s_window_width = 0;
static u32 s_window_height = 0;
static float s_window_scale = 1.0f;
static std::optional<std::string> s_pending_boot_path;
static std::string s_boot_path;
static bool s_game_started = false;
static bool s_fastmem_override_logged = false;
static u32 s_binding_capture_id = 0; // changes when a capture starts or is cancelled, so a stale report is dropped

// Set when the Vulkan device didn't start: games use OpenGL ES until the process ends.
static std::atomic_bool s_vulkan_failed{false};
} // namespace AndroidHost

bool AndroidHost::Initialize(std::string data_root, std::string resources_root, std::string bios_dir,
                             std::string install_id, Error* error)
{
  std::unique_lock lock(s_initialize_mutex);
  if (s_initialized)
    return true;

  // The core's messages go to logcat.
  Log::SetDebugOutputParams(true);

  EmuFolders::AppRoot = Path::Canonicalize(resources_root);
  EmuFolders::Resources = EmuFolders::AppRoot;
  EmuFolders::DataRoot = Path::Canonicalize(data_root);
  s_bios_dir = Path::Canonicalize(bios_dir);
  s_install_id = std::move(install_id);
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
  changed = AdaptForeignSettings(si, s_install_id) || changed;

  // The controllers this install has seen, at the indices their bindings use. An adapted file has none.
  s_descriptors = si.GetStringList(DESCRIPTORS_SECTION, DESCRIPTORS_KEY);

  // Saving the defaults also makes sure the config can be saved at all.
  if (changed && !si.Save(error))
  {
    Error::AddPrefixFmt(error, "Failed to save configuration to '{}': ", si.GetFileName());
    return false;
  }

  // Secrets go to secrets.ini next to it, so settings.ini can be shared without them.
  std::string secrets_filename = Path::Combine(Path::GetDirectory(si.GetFileName()), "secrets.ini");
  const bool secrets_exist = FileSystem::FileExists(secrets_filename.c_str());
  s_secrets_settings_interface = std::make_unique<INISettingsInterface>(std::move(secrets_filename));
  if (secrets_exist && !s_secrets_settings_interface->Load())
    ERROR_LOG("Failed to load secrets from {}.", s_secrets_settings_interface->GetFileName());
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

void AndroidHost::ReloadSettings()
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread(
    []() {
      bool adapted;
      {
        const auto lock = Host::GetSettingsLock();
        INISettingsInterface& si = *s_base_settings_interface;
        Error error;
        if (!si.Reload(&error))
        {
          ERROR_LOG("Failed to reload the settings from {}, keeping them: {}", si.GetFileName(),
                    error.GetDescription());
          return;
        }

        INFO_LOG("Reloaded the settings from {}.", si.GetFileName());
        adapted = AdaptForeignSettings(si, s_install_id);

        // The indices in use stay as they are, whatever the file has: an adapted one has none.
        bool changed = adapted;
        if (si.GetStringList(DESCRIPTORS_SECTION, DESCRIPTORS_KEY) != s_descriptors)
        {
          WriteDescriptors(si);
          changed = true;
        }

        if (changed && !si.Save(&error))
          ERROR_LOG("Failed to save settings: {}", error.GetDescription());
      }

      // A pad connected now that the file leaves with no binding (all of them, after an adapt) won't connect again:
      // map it here.
      for (const auto& device : InputManager::EnumerateDevices())
      {
        const std::optional<u32> index = ParseDeviceIndex(device.first);
        if (!index.has_value())
          continue;

        bool bound;
        {
          const auto lock = Host::GetSettingsLock();
          bound = HasDeviceBinding(*s_base_settings_interface, index.value());
        }

        if (!bound)
          AddControllerToPort1(device.first);
      }

      // Update() takes the lock itself, and loads the folders again.
      EmuFolders::Update();
      EmuFolders::Bios = s_bios_dir;
      System::ApplySettings(false);
    },
    true);
}

bool AndroidHost::GetBoolSetting(const char* section, const char* key, bool default_value)
{
  if (!s_initialized)
    return default_value;

  const auto lock = Host::GetSettingsLock();
  return s_base_settings_interface->GetBoolValue(section, key, default_value);
}

void AndroidHost::SetBoolSetting(const char* section, const char* key, bool value)
{
  if (!s_initialized)
    return;

  const auto lock = Host::GetSettingsLock();
  s_base_settings_interface->SetBoolValue(section, key, value);

  Error error;
  if (!s_base_settings_interface->Save(&error))
    ERROR_LOG("Failed to save settings: {}", error.GetDescription());
}

std::string AndroidHost::GetStringSetting(const char* section, const char* key, const char* default_value)
{
  if (!s_initialized)
    return default_value;

  const auto lock = Host::GetSettingsLock();
  return s_base_settings_interface->GetStringValue(section, key, default_value);
}

void AndroidHost::SetStringSetting(const char* section, const char* key, const char* value)
{
  if (!s_initialized)
    return;

  const auto lock = Host::GetSettingsLock();
  if (value)
    s_base_settings_interface->SetStringValue(section, key, value);
  else
    s_base_settings_interface->DeleteValue(section, key);

  Error error;
  if (!s_base_settings_interface->Save(&error))
    ERROR_LOG("Failed to save settings: {}", error.GetDescription());
}

s32 AndroidHost::GetIntSetting(const char* section, const char* key, s32 default_value)
{
  if (!s_initialized)
    return default_value;

  const auto lock = Host::GetSettingsLock();
  return s_base_settings_interface->GetIntValue(section, key, default_value);
}

void AndroidHost::SetIntSetting(const char* section, const char* key, s32 value)
{
  if (!s_initialized)
    return;

  const auto lock = Host::GetSettingsLock();
  s_base_settings_interface->SetIntValue(section, key, value);

  Error error;
  if (!s_base_settings_interface->Save(&error))
    ERROR_LOG("Failed to save settings: {}", error.GetDescription());
}

void AndroidHost::ApplySettings()
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread([]() { System::ApplySettings(false); }, true);
}

bool AndroidHost::IsVulkanFailed()
{
  return s_vulkan_failed.load();
}

bool AndroidHost::IsVulkanRenderer(GPURenderer renderer)
{
  // As System::CreateGPU() picks the device's API: Software (and Automatic) runs on the preferred API.
  if (renderer == GPURenderer::Automatic)
    renderer = Settings::GetAutomaticRenderer();

  return (Settings::GetRenderAPIForRenderer(renderer) == RenderAPI::Vulkan);
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
        &EmuFolders::GameSettings, &EmuFolders::InputProfiles, &EmuFolders::MemoryCards, &EmuFolders::SaveStates,
        &EmuFolders::Screenshots, &EmuFolders::Shaders, &EmuFolders::Textures, &EmuFolders::UserResources,
        &EmuFolders::Videos})
  {
    const std::optional<std::string> relative = RelativeToDataRoot(*folder);
    if (relative.has_value())
      folders.push_back(relative->empty() ? std::string() : *relative + '/');
  }
  return folders;
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

void AndroidHost::StartGame(std::string path)
{
  // Every StartGame() ends with one NotifyGameStopped(): once the game has stopped, or straight away when it fails to
  // boot, is refused or is cancelled before it boots. The app can't tell which game a late callback was for otherwise.
  if (!s_initialized)
  {
    NotifyError("The core isn't initialized.");
    NotifyGameStopped();
    return;
  }

  EmuThread::RunOnThread(
    [path = std::move(path)]() mutable {
      // A running game isn't replaced: the app shuts it down first. One that's stopping (shut down from inside a
      // frame, and only destroyed once System::Execute() returns) is waited for.
      const System::State state = System::GetState();
      if (state != System::State::Shutdown && state != System::State::Stopping)
      {
        WARNING_LOG("A game is already running, not starting '{}'.", Path::GetFileName(path));
        NotifyError("A game is already running.");
        NotifyGameStopped();
        return;
      }

      if (s_pending_boot_path.has_value())
      {
        INFO_LOG("Replacing the boot of '{}'.", Path::GetFileName(s_pending_boot_path.value()));
        NotifyGameStopped();
      }

      s_pending_boot_path = std::move(path);
      if (state == System::State::Stopping)
        INFO_LOG("Waiting for the last game to stop to start '{}'.", Path::GetFileName(s_pending_boot_path.value()));
      else if (!s_window)
        INFO_LOG("Waiting for a surface to start '{}'.", Path::GetFileName(s_pending_boot_path.value()));

      // The CPU thread's loop boots it once this batch of work is done.
    },
    false);
}

void AndroidHost::BootPendingGame()
{
  // The GPU device is only ever created on a window (a context created surfaceless might not take one later), and a
  // game only boots once the last one is destroyed. A game shut down from inside a frame stays Stopping until
  // System::Execute() returns, so Shutdown also means the core isn't executing.
  if (!s_pending_boot_path.has_value() || !s_window || System::GetState() != System::State::Shutdown)
    return;

  s_boot_path = std::move(s_pending_boot_path.value());
  s_pending_boot_path.reset();

  // Nothing held before the boot carries over into the game, nor stops its first press from counting.
  QueueReleaseAllInputs();

  INFO_LOG("Starting '{}'.", s_boot_path);
  Error error;
  if (!System::BootSystem(SystemBootParameters(s_boot_path), &error))
  {
    ERROR_LOG("Failed to start '{}': {}", s_boot_path, error.GetDescription());
    NotifyError(fmt::format("Failed to start {}: {}", Path::GetFileName(s_boot_path), error.GetDescription()));
    NotifyGameStopped();
  }
}

void AndroidHost::ShutdownGame()
{
  EmuThread::RunOnThread(
    []() {
      if (s_pending_boot_path.has_value())
      {
        INFO_LOG("Cancelling the boot of '{}'.", Path::GetFileName(s_pending_boot_path.value()));
        s_pending_boot_path.reset();
        NotifyGameStopped();
      }

      if (System::IsValid())
        System::ShutdownSystem(false);
    },
    false);
}

void AndroidHost::SetPaused(bool paused)
{
  EmuThread::RunOnThread(
    [paused]() {
      if (!System::IsValid())
        return;

      System::PauseSystem(paused);

      // A paused system's memory cards never reach their delayed save, and the app may be killed in the background:
      // write what the game saved now.
      if (paused)
      {
        for (u32 slot = 0; slot < NUM_CONTROLLER_AND_CARD_PORTS; slot++)
        {
          if (MemoryCard* card = Pad::GetMemoryCard(slot))
            card->Flush();
        }
      }
    },
    false);
}

void AndroidHost::SurfaceChanged(ANativeWindow* window, s32 width, s32 height, float scale)
{
  if (!s_initialized)
  {
    WARNING_LOG("Surface changed before initialization, ignoring it.");
    if (window)
      ANativeWindow_release(window);
    return;
  }

  // Blocks, so the app's surface outlives the GPU device's use of it.
  EmuThread::RunOnThread([window, width, height, scale]() { SetWindow(window, width, height, scale); }, true);
}

void AndroidHost::SetWindow(ANativeWindow* window, s32 width, s32 height, float scale)
{
  if (window)
  {
    s_window_width = static_cast<u32>((width > 0) ? width : ANativeWindow_getWidth(window));
    s_window_height = static_cast<u32>((height > 0) ? height : ANativeWindow_getHeight(window));
    s_window_scale = (scale > 0.0f) ? scale : 1.0f;
  }

  if (window && window == s_window)
  {
    // Same surface, new size. Drop the extra reference the app took for this call.
    ANativeWindow_release(window);
    Host::ResizeDisplayWindow(static_cast<s32>(s_window_width), static_cast<s32>(s_window_height), s_window_scale);
    return;
  }

  if (!window && !s_window)
    return;

  if (window)
    INFO_LOG("New surface, {}x{}.", s_window_width, s_window_height);
  else
    INFO_LOG("Surface destroyed.");

  ANativeWindow* const old_window = s_window;
  s_window = window;

  // Moves the GPU device to the new window, or makes it surfaceless, before the old window goes.
  Host::UpdateDisplayWindow();
  if (old_window)
    ANativeWindow_release(old_window);

  // A boot waiting for this surface isn't started here: SurfaceChanged() blocks the app's UI thread until this returns.
  // The CPU thread's loop boots it once the work is done (EmuThread::ThreadMain()).
}

bool AndroidHost::QueueKeyEvent(s32 device_id, s32 keycode, bool pressed)
{
  {
    std::unique_lock lock(s_input_mutex);
    if (!s_accept_input_events ||
        std::find(s_queued_device_ids.begin(), s_queued_device_ids.end(), device_id) == s_queued_device_ids.end())
    {
      return false;
    }
  }

  // Back and Mode open the menu, so the app keeps them. Every other key can be bound, even with no pad input of its own
  // (BUTTON_C, BUTTON_Z, BUTTON_1 to 16).
  if (keycode == AndroidInputKeys::KEYCODE_BACK || keycode == AndroidInputKeys::KEYCODE_BUTTON_MODE)
    return false;

  QueueInputEvent(InputEvent{InputEvent::Type::Key, device_id, keycode, pressed ? 1.0f : 0.0f, {}, {}});
  return true;
}

void AndroidHost::QueueAxisEvent(s32 device_id, s32 axis, float value)
{
  QueueInputEvent(InputEvent{InputEvent::Type::Axis, device_id, axis, value, {}, {}});
}

void AndroidHost::QueueDeviceConnected(s32 device_id, std::string name, std::string descriptor)
{
  QueueInputEvent(InputEvent{InputEvent::Type::Connected, device_id, 0, 0.0f, std::move(name), std::move(descriptor)});
}

void AndroidHost::QueueDeviceDisconnected(s32 device_id)
{
  QueueInputEvent(InputEvent{InputEvent::Type::Disconnected, device_id, 0, 0.0f, {}, {}});
}

void AndroidHost::QueueReleaseAllInputs()
{
  QueueInputEvent(InputEvent{InputEvent::Type::ReleaseAll, 0, 0, 0.0f, {}, {}});
}

void AndroidHost::QueueInputEvent(InputEvent event)
{
  bool wake;
  {
    std::unique_lock lock(s_input_mutex);
    switch (event.type)
    {
      case InputEvent::Type::Key:
      case InputEvent::Type::Axis:
      case InputEvent::Type::ReleaseAll:
      {
        // Before the CPU thread runs, nothing drains the queue: only connections are kept for it.
        if (!s_accept_input_events)
          return;
      }
      break;

      case InputEvent::Type::Connected:
      {
        if (std::find(s_queued_device_ids.begin(), s_queued_device_ids.end(), event.device_id) ==
            s_queued_device_ids.end())
        {
          s_queued_device_ids.push_back(event.device_id);
        }
      }
      break;

      case InputEvent::Type::Disconnected:
        std::erase(s_queued_device_ids, event.device_id);
        break;
    }

    wake = s_accept_input_events && s_input_queue.empty();
    s_input_queue.push_back(std::move(event));
  }

  // Once per batch, so a game's idle thread picks it up straight away.
  if (wake)
    EmuThread::Wake();
}

void AndroidHost::ProcessInputQueue()
{
  // Give the source every connected device whenever it's created, so none is lost: one connected before it existed
  // (CPUThreadInitialize() creates it), or before the input sources were reloaded. Only CPUThreadShutdown() closes
  // them, so a new source can't reuse the old one's address unseen.
  AndroidInputSource* const source =
    static_cast<AndroidInputSource*>(InputManager::GetInputSourceInterface(InputSourceType::Android));
  if (source != s_input_source)
  {
    s_input_source = source;
    if (source)
    {
      for (const ConnectedDevice& device : s_connected_devices)
        source->QueueDeviceConnected(device.device_id, device.index, device.name);
    }
  }

  {
    std::unique_lock lock(s_input_mutex);
    if (s_input_queue.empty())
      return;

    s_input_events.swap(s_input_queue);
  }

  for (InputEvent& event : s_input_events)
  {
    const auto device = std::find_if(s_connected_devices.begin(), s_connected_devices.end(),
                                     [&event](const ConnectedDevice& d) { return d.device_id == event.device_id; });
    switch (event.type)
    {
      case InputEvent::Type::Key:
      {
        if (source && device != s_connected_devices.end())
          source->QueueKeyEvent(event.device_id, event.code, event.value != 0.0f);
      }
      break;

      case InputEvent::Type::Axis:
      {
        if (source && device != s_connected_devices.end())
          source->QueueAxisEvent(event.device_id, event.code, event.value);
      }
      break;

      case InputEvent::Type::Connected:
      {
        if (device != s_connected_devices.end())
          break;

        bool added;
        const u32 index = AndroidInputKeys::IndexForDescriptor(s_descriptors, event.descriptor, &added);
        if (added)
        {
          INFO_LOG("'{}' is a new controller, {}{}.", event.name, DEVICE_PREFIX, index);
          {
            const auto lock = Host::GetSettingsLock();
            WriteDescriptors(*s_base_settings_interface);
            Error error;
            if (!s_base_settings_interface->Save(&error))
              ERROR_LOG("Failed to save settings: {}", error.GetDescription());
          }

          // A new controller gets the defaults once the source has it (Host::OnInputDeviceConnected()). One seen before
          // keeps the bindings it has, unless it has none (MapNewController()).
          s_unmapped_indices.push_back(index);
        }

        s_connected_devices.push_back(ConnectedDevice{event.device_id, index, event.name});
        if (source)
          source->QueueDeviceConnected(event.device_id, index, std::move(event.name));
      }
      break;

      case InputEvent::Type::Disconnected:
      {
        if (device == s_connected_devices.end())
          break;

        s_connected_devices.erase(device);
        if (source)
          source->QueueDeviceDisconnected(event.device_id);
      }
      break;

      case InputEvent::Type::ReleaseAll:
      {
        // Queued behind the events before it, so none of them is pressed after the release.
        if (source)
          source->QueueReleaseAll();
      }
      break;
    }
  }

  s_input_events.clear();
}

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

const Controller::ControllerInfo* AndroidHost::GetPort1ControllerInfo(SettingsInterface& si)
{
  const std::string section = Controller::GetSettingsSection(0);
  const std::string type = si.GetStringValue(section.c_str(), "Type", Controller::GetDefaultPadType(0));
  const Controller::ControllerInfo* info = Controller::GetControllerInfo(type);
  if (!info)
    WARNING_LOG("Port 1 has an unknown controller type '{}'.", type);

  return info;
}

u32 AndroidHost::AddDefaultBindings(SettingsInterface& si, u32 index)
{
  const Controller::ControllerInfo* info = GetPort1ControllerInfo(si);
  if (!info)
    return 0;

  // As InputManager::MapController(): the first binding string for each of the pad's inputs, added to the ones there.
  const std::string section = Controller::GetSettingsSection(0);
  const GenericInputBindingMapping mapping = AndroidInputKeys::GetDefaultMapping(index);
  u32 added = 0;
  for (const Controller::ControllerBindingInfo& bi : info->bindings)
  {
    if (bi.generic_mapping == GenericInputBinding::Unknown)
      continue;

    const auto it = std::find_if(mapping.begin(), mapping.end(),
                                 [&bi](const auto& entry) { return entry.first == bi.generic_mapping; });
    if (it != mapping.end() && si.AddToStringList(section.c_str(), bi.name, it->second.c_str()))
      added++;
  }

  return added;
}

bool AndroidHost::HasDeviceBinding(SettingsInterface& si, u32 index)
{
  std::vector<std::string> bindings;
  for (auto& [key, value] : si.GetKeyValueList(Controller::GetSettingsSection(0).c_str()))
    bindings.push_back(std::move(value));

  return AndroidInputKeys::GetDeviceBinding(bindings, index).has_value();
}

bool AndroidHost::AddControllerToPort1(const std::string& identifier)
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

  // Every controller drives Port 1: its default bindings are added to the ones there, so a pad connected later (a
  // Bluetooth pad next to a handheld's own) works too.
  {
    const auto lock = Host::GetSettingsLock();
    SettingsInterface& si = *s_base_settings_interface;
    const u32 added = AddDefaultBindings(si, index.value());
    if (added == 0)
      return true;

    INFO_LOG("Added {} bindings for {} to Port 1.", added, identifier);
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

  // A new controller, or one seen before with no binding of its own on Port 1, which would be dead. One with even a
  // single binding keeps what it has, so a remap survives.
  if (std::find(s_unmapped_indices.begin(), s_unmapped_indices.end(), index.value()) == s_unmapped_indices.end())
  {
    const auto lock = Host::GetSettingsLock();
    if (HasDeviceBinding(*s_base_settings_interface, index.value()))
      return;
  }

  // One that's gone again stays unmapped, and gets its defaults when it's back.
  if (AddControllerToPort1(identifier))
    std::erase(s_unmapped_indices, index.value());
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

      const auto lock = Host::GetSettingsLock();
      SettingsInterface& si = *s_base_settings_interface;
      result.emplace();
      const Controller::ControllerInfo* info = GetPort1ControllerInfo(si);
      if (!info)
        return;

      const std::string section = Controller::GetSettingsSection(0);
      for (const Controller::ControllerBindingInfo& bi : info->bindings)
      {
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

        result->push_back(PadBinding{bi.name, bi.display_name, kind,
                                     AndroidInputKeys::GetDeviceBinding(si.GetStringList(section.c_str(), bi.name),
                                                                        index.value())});
      }
    },
    true);
  return result;
}

std::optional<u32> AndroidHost::GetControllerIndex(std::string descriptor)
{
  if (!s_initialized)
    return std::nullopt;

  std::optional<u32> result;
  EmuThread::RunOnThread([&descriptor, &result]() { result = FindDescriptorIndex(descriptor); }, true);
  return result;
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

      {
        const auto lock = Host::GetSettingsLock();
        SettingsInterface& si = *s_base_settings_interface;
        // Only what GetPadBindings() lists: a button or an axis, never a pointer or a motor.
        const Controller::ControllerInfo* info = GetPort1ControllerInfo(si);
        if (!info || std::none_of(info->bindings.begin(), info->bindings.end(),
                                  [&name](const Controller::ControllerBindingInfo& bi) {
                                    return name == bi.name && IsPadBindingType(bi.type);
                                  }))
        {
          WARNING_LOG("Port 1 has no button or axis '{}', not binding it.", name);
          return;
        }

        // An edit made before a new pad's defaults are mapped: they'd be added over it.
        std::erase(s_unmapped_indices, index.value());

        const std::string section = Controller::GetSettingsSection(0);
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

void AndroidHost::ResetPadBindings(std::string descriptor)
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread(
    [&descriptor]() {
      const std::optional<u32> index = FindDescriptorIndex(descriptor);
      if (!index.has_value())
        return;

      {
        const auto lock = Host::GetSettingsLock();
        SettingsInterface& si = *s_base_settings_interface;
        const std::string section = Controller::GetSettingsSection(0);

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

        AddDefaultBindings(si, index.value());

        Error error;
        if (!si.Save(&error))
          ERROR_LOG("Failed to save settings: {}", error.GetDescription());
      }

      System::ReloadInputBindings();
    },
    true);
}

void AndroidHost::StartBindingCapture(std::string descriptor)
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread(
    [&descriptor]() {
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
      AndroidInputKeys::BindingCapture capture(index.value(),
                                               source ? source->GetCenteredAxes(index.value()) : std::vector<u32>());
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

void Host::LoadSettings(SettingsInterface& si, std::unique_lock<std::mutex>& lock)
{
  // Settings::UpdateLogSettings() follows Logging/LogToDebug, but on Android the core always logs to logcat.
  g_settings.log_to_debug = true;
  Log::SetDebugOutputParams(true);

  // The build's page size is a multiple of 4 KB pages, but fastmem can't map pages smaller than the system's.
  const long system_page_size = sysconf(_SC_PAGESIZE);
  if (system_page_size > static_cast<long>(HOST_PAGE_SIZE) && g_settings.cpu_fastmem_mode != CPUFastmemMode::Disabled)
  {
    if (!AndroidHost::s_fastmem_override_logged)
    {
      WARNING_LOG("The system has {} byte pages, larger than this build's {}: fastmem is disabled.", system_page_size,
                  HOST_PAGE_SIZE);
      AndroidHost::s_fastmem_override_logged = true;
    }

    g_settings.cpu_fastmem_mode = CPUFastmemMode::Disabled;
  }

  // Once Vulkan has failed, every renderer on it uses OpenGL ES instead, so later boots don't try it again. The
  // software renderer is left as it is.
  if (AndroidHost::s_vulkan_failed.load() && g_settings.gpu_renderer != GPURenderer::Software &&
      AndroidHost::IsVulkanRenderer(g_settings.gpu_renderer))
  {
    g_settings.gpu_renderer = GPURenderer::HardwareOpenGL;
  }
}

bool Host::OnVulkanDeviceFailed(const Error& error)
{
  WARNING_LOG("Vulkan didn't start ({}), using OpenGL ES until the app restarts.", error.GetDescription());
  AndroidHost::s_vulkan_failed.store(true);
  if (g_settings.gpu_renderer != GPURenderer::Software)
    g_settings.gpu_renderer = GPURenderer::HardwareOpenGL;
  Host::AddOSDMessage("Vulkan didn't start on this device, so GooseStation is using OpenGL ES.",
                      Host::OSD_WARNING_DURATION);
  return true;
}

void Host::CheckForSettingsChanges(const Settings& old_settings)
{
}

void Host::CommitBaseSettingChanges()
{
  const auto lock = Host::GetSettingsLock();
  Error error;
  if (!AndroidHost::s_base_settings_interface->Save(&error))
    ERROR_LOG("Failed to save settings: {}", error.GetDescription());
}

void Host::ReportFatalError(std::string_view title, std::string_view message)
{
  ERROR_LOG("ReportFatalError: {}: {}", title, message);
  AndroidHost::NotifyError(message);
  Host::RunOnCPUThread([]() {
    if (System::IsValid())
      System::ShutdownSystem(false);
  });
}

void Host::ReportErrorAsync(std::string_view title, std::string_view message)
{
  ERROR_LOG("ReportErrorAsync: {}: {}", title, message);
  AndroidHost::NotifyError(message.empty() ? title : message);
}

bool Host::ConfirmMessage(std::string_view title, std::string_view message)
{
  // No dialogs yet: go ahead, as the headless host does.
  WARNING_LOG("ConfirmMessage (confirmed): {}: {}", title, message);
  return true;
}

void Host::ReportDebuggerMessage(std::string_view message)
{
  INFO_LOG("Debugger: {}", message);
}

s32 Host::Internal::GetTranslatedStringImpl(std::string_view context, std::string_view msg, char* tbuf,
                                            size_t tbuf_space)
{
  // No translations yet: the string as it is.
  if (msg.size() > tbuf_space)
    return -1;

  std::memcpy(tbuf, msg.data(), msg.size());
  return static_cast<s32>(msg.size());
}

std::string Host::TranslatePluralToString(const char* context, const char* msg, const char* disambiguation, int count)
{
  return std::string(TranslatePluralToSmallString(context, msg, disambiguation, count).view());
}

SmallString Host::TranslatePluralToSmallString(const char* context, const char* msg, const char* disambiguation,
                                               int count)
{
  SmallString ret(msg);
  ret.replace("%n", TinyString::from_format("{}", count));
  return ret;
}

static std::string GetResourcePath(std::string_view filename, bool allow_override)
{
  return allow_override ? EmuFolders::GetOverridableResourcePath(filename) :
                          Path::Combine(EmuFolders::Resources, filename);
}

bool Host::ResourceFileExists(std::string_view filename, bool allow_override)
{
  const std::string path = GetResourcePath(filename, allow_override);
  return FileSystem::FileExists(path.c_str());
}

std::optional<DynamicHeapArray<u8>> Host::ReadResourceFile(std::string_view filename, bool allow_override)
{
  const std::string path = GetResourcePath(filename, allow_override);
  std::optional<DynamicHeapArray<u8>> ret = FileSystem::ReadBinaryFile(path.c_str());
  if (!ret.has_value())
    ERROR_LOG("Failed to read resource file '{}'", filename);
  return ret;
}

std::optional<std::string> Host::ReadResourceFileToString(std::string_view filename, bool allow_override)
{
  const std::string path = GetResourcePath(filename, allow_override);
  std::optional<std::string> ret = FileSystem::ReadFileToString(path.c_str());
  if (!ret.has_value())
    ERROR_LOG("Failed to read resource file to string '{}'", filename);
  return ret;
}

std::optional<std::time_t> Host::GetResourceFileTimestamp(std::string_view filename, bool allow_override)
{
  const std::string path = GetResourcePath(filename, allow_override);
  FILESYSTEM_STAT_DATA sd;
  if (!FileSystem::StatFile(path.c_str(), &sd))
  {
    ERROR_LOG("Failed to stat resource file '{}'", filename);
    return std::nullopt;
  }

  return sd.ModificationTime;
}

void Host::RunOnCPUThread(std::function<void()> function, bool block /* = false */)
{
  EmuThread::RunOnThread(std::move(function), block);
}

void Host::PumpMessagesOnCPUThread()
{
  EmuThread::ProcessPendingWork();
}

void Host::OnSystemStarting()
{
}

void Host::OnSystemStarted()
{
  const std::string& title = System::GetGameTitle();
  INFO_LOG("Game started: {}", title);
  AndroidHost::s_game_started = true;
  AndroidHost::NotifyGameStarted(title.empty() ? Path::GetFileTitle(AndroidHost::s_boot_path) : title);
}

void Host::OnSystemDestroyed()
{
  // Whatever is held now is let go, so it doesn't stay pressed into the next game, nor swallow its first press.
  AndroidHost::QueueReleaseAllInputs();

  // A boot that failed destroys the system too: BootPendingGame() reports that one.
  if (!AndroidHost::s_game_started)
    return;

  INFO_LOG("Game stopped.");
  AndroidHost::s_game_started = false;
  AndroidHost::NotifyGameStopped();
}

void Host::OnSystemPaused()
{
}

void Host::OnSystemResumed()
{
}

void Host::OnIdleStateChanged()
{
  EmuThread::Wake();
}

void Host::OnPerformanceCountersUpdated()
{
}

void Host::OnGameChanged(const std::string& disc_path, const std::string& game_serial, const std::string& game_name)
{
  INFO_LOG("Game changed: '{}' ({}), {}", game_name, game_serial, disc_path);
}

void Host::RequestResizeHostDisplay(s32 width, s32 height)
{
  // The app's surface is always full screen.
}

void Host::FrameDone()
{
}

std::optional<WindowInfo> Host::AcquireRenderWindow(bool recreate_window)
{
  // Test only, for the OpenGL ES fallback: with debug.goosestation.fail_vulkan = 1 (adb shell setprop), creating a
  // Vulkan device fails until the fallback has happened. Set the property before starting a game: any Vulkan device
  // created after that fails, such as a switch to Vulkan during a game. Surface changes keep the device, so they don't
  // come here with recreate_window. The API is worked out from the settings, as System::CreateGPU() does, because
  // asking the device isn't safe yet: OpenGLDevice::GetRenderAPI() reads a context it hasn't created.
  if (recreate_window && !AndroidHost::s_vulkan_failed.load())
  {
    char value[PROP_VALUE_MAX] = {};
    if (__system_property_get("debug.goosestation.fail_vulkan", value) > 0 && std::strcmp(value, "1") == 0 &&
        AndroidHost::IsVulkanRenderer(g_settings.gpu_renderer))
    {
      ERROR_LOG("Failing the Vulkan device for testing (debug.goosestation.fail_vulkan).");
      return std::nullopt;
    }
  }

  WindowInfo wi;
  if (!AndroidHost::s_window)
  {
    // Creating the device needs a window; an existing device goes surfaceless.
    if (recreate_window)
    {
      ERROR_LOG("No surface to create the GPU device on.");
      return std::nullopt;
    }

    wi.SetSurfaceless();
    return wi;
  }

  wi.type = WindowInfo::Type::Android;
  wi.window_handle = AndroidHost::s_window;
  wi.surface_width = AndroidHost::s_window_width;
  wi.surface_height = AndroidHost::s_window_height;
  wi.surface_scale = AndroidHost::s_window_scale;
  return wi;
}

void Host::ReleaseRenderWindow()
{
  // The window belongs to the app's surface, released when the surface goes.
}

bool Host::CopyTextToClipboard(std::string_view text)
{
  return false;
}

std::string Host::GetTextFromClipboard()
{
  return {};
}

void Host::OnTextInputStarted(const TextInput::Request& request)
{
}

void Host::OnTextInputEnded()
{
}

void Host::SetMouseMode(bool relative, bool hide_cursor)
{
}

void Host::OnAchievementsLoginRequested(Achievements::LoginRequestReason reason)
{
}

void Host::OnAchievementsLoginSuccess(const char* username, u32 points, u32 sc_points, u32 unread_messages)
{
}

void Host::OnAchievementsRefreshed()
{
}

void Host::OnAchievementsHardcoreModeChanged(bool enabled)
{
}

bool Host::ShouldPreferHostFileSelector()
{
  return false;
}

void Host::OpenHostFileSelectorAsync(std::string_view title, bool select_directory, FileSelectorCallback callback,
                                     FileSelectorFilters filters /* = FileSelectorFilters() */,
                                     std::string_view initial_directory /* = std::string_view() */)
{
  callback(std::string());
}

void Host::AddFixedInputBindings(SettingsInterface& si)
{
}

void Host::OnInputDeviceConnected(std::string_view identifier, std::string_view device_name)
{
  // The source reports this from inside its poll: map a new controller after it, not while the bindings are in use.
  Host::RunOnCPUThread(
    [identifier = std::string(identifier)]() { AndroidHost::MapNewController(identifier); });
}

void Host::OnInputDeviceDisconnected(InputBindingKey key, std::string_view identifier)
{
}

// No keyboard support yet.

std::optional<u32> InputManager::ConvertHostKeyboardStringToCode(std::string_view str)
{
  return std::nullopt;
}

std::optional<std::string> InputManager::ConvertHostKeyboardCodeToString(u32 code)
{
  return std::nullopt;
}

const char* InputManager::ConvertHostKeyboardCodeToIcon(u32 code)
{
  return nullptr;
}

// No host hotkeys yet.
BEGIN_HOTKEY_LIST(g_host_hotkeys)
END_HOTKEY_LIST()
