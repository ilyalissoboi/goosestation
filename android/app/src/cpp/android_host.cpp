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

static void QueueInputEvent(InputEvent event);

static void SetWindow(ANativeWindow* window, s32 width, s32 height, float scale, float refresh_rate);

static std::optional<u32> FindDescriptorIndex(std::string_view descriptor);

static const Controller::ControllerInfo* GetPadControllerInfo(SettingsInterface& si, u32 pad);

static std::vector<const Controller::ControllerInfo*> GetGameTypes(u32 pad);

static std::string GetPortLabel(u32 pad, const std::array<bool, 2>& multitap);

static bool PadHasAnalogButton(SettingsInterface& si, u32 pad);

static std::vector<const HotkeyInfo*> GetOfferedHotkeys(SettingsInterface& si, u32 pad);

static void ApplyTouchPad();

// Plan 1's Android settings.ini: the same keys as now, so it's upgraded in place.
static constexpr u32 PLAN1_SETTINGS_VERSION = 1;

static constexpr const char* PORTS_KEY = AndroidInputKeys::PORTS_KEY;

static constexpr const char* RUMBLE_KEY = AndroidInputKeys::RUMBLE_KEY;

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

static std::vector<InputEvent> s_input_queue;

static std::vector<s32> s_queued_device_ids; // connected as far as the queue goes, for QueueKeyEvent()'s result

// CPU thread only.
static std::vector<InputEvent> s_input_events;

static AndroidInputSource* s_input_source = nullptr;

static bool s_game_started = false;

static bool s_fastmem_override_logged = false;

static bool s_fast_forward_reported = false; // as the app was last told, CPU thread

static std::atomic<u32> s_touch_presses_for_test{0}; // the buttons given as pressed, for TakeTouchPressesForTest()

static s32 s_pad_kind_reported = -1;    // Port 1's kind, as the app was last told, CPU thread

static u32 s_binding_capture_id = 0; // changes when a capture starts or is cancelled, so a stale report is dropped

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

namespace AndroidHost {
MemcardSyncSettings ReadMemcardSyncSettings(const SettingsInterface& si)
{
  return {si.GetBoolValue(MemcardSync::SECTION, "Enabled", false), si.GetStringValue(MemcardSync::SECTION, "RemoteURI"),
          si.GetStringValue(MemcardSync::SECTION, "AccessKeyID"), si.GetStringValue(MemcardSync::SECTION, "DeviceName")};
}

} // namespace AndroidHost

void AndroidHost::SurfaceChanged(ANativeWindow* window, s32 width, s32 height, float scale, float refresh_rate)
{
  if (!s_initialized)
  {
    WARNING_LOG("Surface changed before initialization, ignoring it.");
    if (window)
      ANativeWindow_release(window);
    return;
  }

  // Blocks, so the app's surface outlives the GPU device's use of it.
  EmuThread::RunOnThread(
    [window, width, height, scale, refresh_rate]() { SetWindow(window, width, height, scale, refresh_rate); }, true);
}

void AndroidHost::SetWindow(ANativeWindow* window, s32 width, s32 height, float scale, float refresh_rate)
{
  if (window)
  {
    s_window_width = static_cast<u32>((width > 0) ? width : ANativeWindow_getWidth(window));
    s_window_height = static_cast<u32>((height > 0) ? height : ANativeWindow_getHeight(window));
    s_window_scale = (scale > 0.0f) ? scale : 1.0f;
    s_window_refresh_rate = std::max(refresh_rate, 0.0f);
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

void AndroidHost::QueueKeyEvents(s32 device_id, std::span<const std::pair<s32, bool>> keys)
{
  bool wake;
  {
    std::unique_lock lock(s_input_mutex);
    if (!s_accept_input_events ||
        std::find(s_queued_device_ids.begin(), s_queued_device_ids.end(), device_id) == s_queued_device_ids.end())
    {
      return;
    }

    // All under the one lock: the CPU thread takes the queue whole, so it sees them together.
    wake = s_input_queue.empty();
    for (const auto& [keycode, pressed] : keys)
    {
      if (keycode == AndroidInputKeys::KEYCODE_BACK || keycode == AndroidInputKeys::KEYCODE_BUTTON_MODE)
        continue;

      s_input_queue.push_back(InputEvent{InputEvent::Type::Key, device_id, keycode, pressed ? 1.0f : 0.0f, {}, {}});
    }
  }

  if (wake)
    EmuThread::Wake();
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

namespace AndroidHost {
// With s_input_mutex held: queues state, and says whether the CPU thread needs waking for it.
static bool QueueTouchPadLocked(const TouchPad::State& state)
{
  if (!s_accept_input_events)
    return false;

  // Only the latest matters: a state still queued is replaced, and its wake is still to come. A press it had that the
  // latest lets go of is kept apart, so a tap shorter than a pass still reaches the game.
  const bool wake = !s_touch_queued.has_value() && s_input_queue.empty();
  if (s_touch_queued.has_value())
    s_touch_tapped |= s_touch_queued->buttons & ~state.buttons;
  s_touch_queued = state;
  return wake;
}

} // namespace AndroidHost

void AndroidHost::QueueTouchPad(const TouchPad::State& state)
{
  bool wake;
  {
    std::unique_lock lock(s_input_mutex);
    wake = QueueTouchPadLocked(state);
  }

  if (wake)
    EmuThread::Wake();
}

void AndroidHost::QueueTouchPadsForTest(std::span<const u32> buttons)
{
  bool wake = false;
  {
    std::unique_lock lock(s_input_mutex);
    for (const u32 pressed : buttons)
      wake = QueueTouchPadLocked(TouchPad::State{pressed, {}}) || wake;
  }

  if (wake)
    EmuThread::Wake();
}

u32 AndroidHost::TakeTouchPressesForTest()
{
  return s_touch_presses_for_test.exchange(0);
}

void AndroidHost::SetPictureAtTop(bool top)
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread(
    [top]() {
      s_picture_at_top = top;
      g_settings.display_alignment = top ? DisplayAlignment::LeftOrTop : s_loaded_display_alignment;
    },
    false);
}

// CPU thread: gives Port 1's controller what the touch pad holds. While it holds anything, its inputs are set again
// each pass, so a controller the core made anew (a type change) takes them too.
void AndroidHost::ApplyTouchPad()
{
  Controller* const controller = System::IsValid() ? System::GetController(0) : nullptr;
  if (!controller)
  {
    s_touch_applied = {};
    return;
  }

  if (s_touch_state != s_touch_applied)
  {
    s_touch_presses_for_test.fetch_or(s_touch_state.buttons & ~s_touch_applied.buttons);
    TouchPad::Apply(*controller, s_touch_applied, s_touch_state);
    const bool let_go = TouchPad::LetsGo(s_touch_applied, s_touch_state);
    s_touch_applied = s_touch_state;

    // Touch controls on Always beside a controller on Port 1: what the touch pad let go of is held again where the
    // controller still holds it, in this same pass.
    if (let_go && s_input_source)
      s_input_source->ReportHeld();
  }
  else if (s_touch_state != TouchPad::State{})
  {
    TouchPad::Reassert(*controller, s_touch_state);
  }
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
      source->SetRumbleCallback([](s32 device_id, float large, float small) { NotifyRumble(device_id, large, small); });
      UpdateSourceControllers();
    }
  }

  {
    std::unique_lock lock(s_input_mutex);
    if (s_touch_queued.has_value())
    {
      s_touch_state = s_touch_queued.value();
      if (s_touch_tapped != 0)
      {
        // A tap let go before this pass: pressed for this one, let go at the next, with the latest state, still queued.
        s_touch_state.buttons |= s_touch_tapped;
        s_touch_tapped = 0;
      }
      else
      {
        s_touch_queued.reset();
      }
    }

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
  ApplyTouchPad();
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

void Host::LoadSettings(SettingsInterface& si, std::unique_lock<std::mutex>& lock)
{
  // Settings::UpdateLogSettings() follows Logging/LogToDebug, but on Android the core always logs to logcat.
  g_settings.log_to_debug = true;
  Log::SetDebugOutputParams(true);

  // The app shows fast forward over the game itself (OsdLayer), and the paused game has its menu: the core's own
  // status icons would show the same things twice.
  g_settings.display_show_status_indicators = false;

  // The touch pad in portrait puts the picture at the top while it shows (SetPictureAtTop()); the setting is kept for
  // when it goes, and never written.
  AndroidHost::s_loaded_display_alignment = g_settings.display_alignment;
  if (AndroidHost::s_picture_at_top)
    g_settings.display_alignment = DisplayAlignment::LeftOrTop;

  // The host owns each game's patch code list and gives it to the core itself (GameCodes), and the core applies
  // whatever list it's given. With this on, the core would also load each disc's own .cht file as it starts, and drop
  // the list when the setting changes. It's never written back: desktop keeps its own setting in a shared settings.ini.
  g_settings.enable_cheats = false;

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
  // A file the library didn't list is keyed by the core's ID for it.
  if (AndroidHost::s_running_game.has_value() && AndroidHost::s_running_game->key.empty())
    AndroidHost::s_running_game->key = System::GetGameSerial();
  // As the core has them for this game, on the CPU thread: the app opens the menu for these itself.
  AndroidHost::NotifyGameStarted(title.empty() ? Path::GetFileTitle(AndroidHost::s_boot_path) : title,
                                 System::GetGameSerial(), g_settings.start_paused,
                                 g_settings.pause_on_controller_disconnection);
  AndroidHost::AddRunningTypesDefaults();
  AndroidHost::PostUnusedPortMessages();
}

void Host::AdaptGameSettingsLayer(SettingsInterface& si)
{
  AndroidGameSettings::Usable usable;
  for (u32 type = 0; type < static_cast<u32>(ControllerType::Count); type++)
  {
    if (const Controller::ControllerInfo* info = Controller::GetControllerInfo(static_cast<ControllerType>(type)))
    {
      for (const SettingInfo& setting : info->settings)
        usable.pad_setting_keys.emplace_back(setting.name);
    }
  }
  for (const GPURenderer renderer : {GPURenderer::Automatic, GPURenderer::HardwareVulkan, GPURenderer::HardwareOpenGL,
                                     GPURenderer::Software})
  {
    usable.renderers.emplace_back(Settings::GetRendererName(renderer));
  }
  // The CPU thread's, which loads the layer.
  const std::string bios_dir = EmuFolders::Bios;
  usable.has_bios = [&bios_dir](std::string_view name) {
    return FileSystem::FileExists(Path::Combine(bios_dir, name).c_str());
  };
  AndroidGameSettings::Adapt(si, usable);
}

void Host::OnSystemDestroyed()
{
  // Whatever is held now is let go, so it doesn't stay pressed into the next game, nor swallow its first press.
  AndroidHost::QueueReleaseAllInputs();
  AndroidHost::s_running_game.reset();
  System::SetGameSettingsSerial({});
  GameCodes::Clear();

  // So do the touch pad's, and the app hears Port 1's kind is gone.
  {
    std::unique_lock lock(AndroidHost::s_input_mutex);
    AndroidHost::s_touch_queued.reset();
    AndroidHost::s_touch_tapped = 0;
  }
  AndroidHost::s_touch_state = {};
  AndroidHost::s_touch_applied = {};
  if (AndroidHost::s_pad_kind_reported != -1)
  {
    AndroidHost::s_pad_kind_reported = -1;
    AndroidHost::NotifyPadKindChanged(-1);
  }

  // Fast forward ends with the game.
  if (AndroidHost::s_fast_forward_reported)
  {
    AndroidHost::s_fast_forward_reported = false;
    AndroidHost::NotifyFastForwardChanged(false);
  }

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

  // The core drops its code list at every change of disc or game: it gets the game's one list again.
  GameCodes::Apply();
}

std::optional<Host::SaveStateMedia> Host::ResolveSaveStateMedia(std::string_view serial, std::string_view media_path,
                                                                u32 subimage_index, Error* error)
{
  // The disc in the drive now (the undo state, or a state of this disc), or a game without listed discs: as it is.
  if (!AndroidHost::s_running_game.has_value() || AndroidHost::s_running_game->discs.empty() ||
      media_path == System::GetMediaFileName())
  {
    return SaveStateMedia{std::string(media_path), subimage_index};
  }

  const AndroidDiscGroups::Game& game = AndroidHost::s_running_game.value();
  if (const std::optional<u32> number = AndroidDiscGroups::FindStateDisc(game, serial, media_path, subimage_index))
  {
    const auto disc = std::find_if(game.discs.begin(), game.discs.end(),
                                   [&number](const AndroidDiscGroups::Disc& d) { return d.number == *number; });
    const std::string& path = game.playlist ? game.path : disc->path;
    if (FileSystem::FileExists(path.c_str()))
    {
      INFO_LOG("The state's disc '{}' is disc {} here: '{}'.", Path::GetFileName(media_path), *number, path);
      return SaveStateMedia{path, game.playlist ? (*number - 1) : 0};
    }
  }

  WARNING_LOG("No disc here for the state's '{}' ({}, sub-image {}).", media_path, serial, subimage_index);
  Error::SetString(error, MissingDiscMessage(media_path));
  return std::nullopt;
}

void Host::RequestResizeHostDisplay(s32 width, s32 height)
{
  // The app's surface is always full screen.
}

void Host::FrameDone()
{
  // Hardcore mode allows no fast forward or turbo, which the core doesn't refuse: a hotkey's, or one already on when a
  // reset started hardcore, is turned off here.
  if (Achievements::IsHardcoreModeActive())
  {
    if (System::IsFastForwardEnabled())
      System::SetFastForwardEnabled(false);
    if (System::IsTurboEnabled())
      System::SetTurboEnabled(false);
  }

  // The app shows a marker while fast forward is on, whether the menu or a hotkey turned it on.
  const bool fast_forward = System::IsFastForwardEnabled();
  if (fast_forward != AndroidHost::s_fast_forward_reported)
  {
    AndroidHost::s_fast_forward_reported = fast_forward;
    AndroidHost::NotifyFastForwardChanged(fast_forward);
  }

  // The touch pad shows Port 1 as it is: sticks in analog mode (the Analog button, the game, a type change).
  const s32 kind = TouchPad::KindOf(System::GetController(0));
  if (kind != AndroidHost::s_pad_kind_reported)
  {
    AndroidHost::s_pad_kind_reported = kind;
    AndroidHost::NotifyPadKindChanged(kind);
  }
}

void Host::OnOSDMessagePosted(std::string_view key, std::string_view message, float duration, const OSDImage* image)
{
  // An icon message is the icon, two spaces, then the text.
  const std::string text = ImGuiManager::StripIconCharacters(message);
  const std::string_view trimmed = StringUtil::StripWhitespace(text);
  if (image && image->width > 0 && image->frame_height > 0 &&
      image->pixels.size() >= static_cast<size_t>(image->width) * image->frame_height)
  {
    AndroidHost::NotifyOsdMessage(key, trimmed, duration, image->pixels.data(), image->width, image->frame_height);
  }
  else
  {
    AndroidHost::NotifyOsdMessage(key, trimmed, duration, nullptr, 0, 0);
  }
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
  // Sync to host refresh rate compares this with the game's rate.
  wi.surface_refresh_rate = AndroidHost::s_window_refresh_rate;
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

// The save state hotkeys, the core's names (compiled out of its own list on Android): the app saves, loads and picks
// the slot, as its menu does, under the game's key and with its messages. On the chord's release, as the core's.
#define APP_STATE_HOTKEY(name, display_name)                                                                           \
  DEFINE_HOTKEY(name, TRANSLATE_NOOP("Hotkeys", "Save States"), TRANSLATE_NOOP("Hotkeys", display_name),               \
                [](s32 pressed) {                                                                                      \
                  if (pressed == 0)                                                                                    \
                    AndroidHost::NotifyHotkey(name);                                                                   \
                })

BEGIN_HOTKEY_LIST(g_host_hotkeys)

APP_STATE_HOTKEY("LoadSelectedSaveState", "Load From Selected Slot")

APP_STATE_HOTKEY("SaveSelectedSaveState", "Save To Selected Slot")

APP_STATE_HOTKEY("SelectPreviousSaveStateSlot", "Select Previous Save Slot")

APP_STATE_HOTKEY("SelectNextSaveStateSlot", "Select Next Save Slot")

APP_STATE_HOTKEY("SaveStateAndSelectNextSlot", "Save State and Select Next Slot")

APP_STATE_HOTKEY("UndoLoadState", "Undo Load State")

// The Controllers page's Analog mode hotkey: the DualShock's Analog button, on the chord's release, as the core's
// toggles are.
DEFINE_HOTKEY("ToggleAnalogMode", TRANSLATE_NOOP("Hotkeys", "Controller"), TRANSLATE_NOOP("Hotkeys", "Toggle Analog Mode"),
              [](s32 pressed) {
                if (pressed == 0)
                  AndroidHost::PressAnalogButton();
              })

END_HOTKEY_LIST()
