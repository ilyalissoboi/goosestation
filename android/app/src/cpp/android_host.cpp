// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_host.h"
#include "emu_thread.h"

#include "core/achievements.h"
#include "core/controller.h"
#include "core/fullscreen_ui.h"
#include "core/host.h"
#include "core/memory_card.h"
#include "core/pad.h"
#include "core/settings.h"
#include "core/system.h"

#include "util/android_input_keys.h"
#include "util/android_input_source.h"
#include "util/gpu_device.h"
#include "util/imgui_fullscreen.h"
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
  std::string name;
  std::string descriptor;
};
} // namespace

static bool InitializeConfig(Error* error);
static void SetDefaultSettings(SettingsInterface& si);
static void QueueInputEvent(InputEvent event);
static void SetWindow(ANativeWindow* window, s32 width, s32 height, float scale);
static void AddControllerToPort1(const std::string& identifier);

// Android's own settings.ini, so its version is independent of the Qt frontend's.
static constexpr u32 SETTINGS_VERSION = 1;

static constexpr std::string_view DEVICE_PREFIX = "Android-";

static std::mutex s_initialize_mutex;
static std::atomic_bool s_initialized{false};
static bool s_startup_attempted = false; // guarded by s_initialize_mutex
static std::unique_ptr<INISettingsInterface> s_base_settings_interface;
static std::unique_ptr<INISettingsInterface> s_secrets_settings_interface;

// Input queued by the app's threads.
static std::mutex s_input_mutex;
static std::vector<InputEvent> s_input_queue;
static std::vector<s32> s_queued_device_ids; // connected as far as the queue goes, for QueueKeyEvent()'s result
static bool s_accept_input_events = false;   // key and axis events are dropped until the CPU thread runs

// CPU thread only.
static std::vector<InputEvent> s_input_events;
static std::vector<ConnectedDevice> s_connected_devices;
static AndroidInputSource* s_input_source = nullptr;
static ANativeWindow* s_window = nullptr;
static u32 s_window_width = 0;
static u32 s_window_height = 0;
static float s_window_scale = 1.0f;
static std::optional<std::string> s_pending_boot_path;
static std::string s_boot_path;
static bool s_game_started = false;
static bool s_fastmem_override_logged = false;
} // namespace AndroidHost

bool AndroidHost::Initialize(std::string data_root, std::string resources_root, Error* error)
{
  std::unique_lock lock(s_initialize_mutex);
  if (s_initialized)
    return true;

  // The core's messages go to logcat.
  Log::SetDebugOutputParams(true);

  EmuFolders::AppRoot = Path::Canonicalize(resources_root);
  EmuFolders::Resources = EmuFolders::AppRoot;
  EmuFolders::DataRoot = Path::Canonicalize(data_root);
  INFO_LOG("DataRoot Directory: {}", EmuFolders::DataRoot);
  INFO_LOG("Resources Directory: {}", EmuFolders::Resources);

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

  if (!InitializeConfig(error) || !EmuThread::Start(error))
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

  u32 settings_version;
  if (!settings_exists || !s_base_settings_interface->Load() ||
      !s_base_settings_interface->GetUIntValue("Main", "SettingsVersion", &settings_version) ||
      settings_version != SETTINGS_VERSION)
  {
    if (s_base_settings_interface->ContainsValue("Main", "SettingsVersion"))
    {
      WARNING_LOG("Settings version {} does not match expected version {}, resetting.", settings_version,
                  SETTINGS_VERSION);
    }

    s_base_settings_interface->SetUIntValue("Main", "SettingsVersion", SETTINGS_VERSION);
    SetDefaultSettings(*s_base_settings_interface);

    // Make sure the config can be saved at all.
    if (!s_base_settings_interface->Save(error))
    {
      Error::AddPrefixFmt(error, "Failed to save configuration to '{}': ", s_base_settings_interface->GetFileName());
      return false;
    }
  }

  // Secrets go to secrets.ini next to it, so settings.ini can be shared without them.
  std::string secrets_filename =
    Path::Combine(Path::GetDirectory(s_base_settings_interface->GetFileName()), "secrets.ini");
  const bool secrets_exist = FileSystem::FileExists(secrets_filename.c_str());
  s_secrets_settings_interface = std::make_unique<INISettingsInterface>(std::move(secrets_filename));
  if (secrets_exist && !s_secrets_settings_interface->Load())
    ERROR_LOG("Failed to load secrets from {}.", s_secrets_settings_interface->GetFileName());
  Host::Internal::SetSecretsSettingsLayer(s_secrets_settings_interface.get());

  EmuFolders::LoadConfig(*s_base_settings_interface);
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

  // Back and Mode have no pad input, so the app keeps them.
  if (!AndroidInputKeys::GetBindingForKeyCode(keycode).has_value())
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
        source->QueueDeviceConnected(device.device_id, device.name, device.descriptor);
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

        s_connected_devices.push_back(ConnectedDevice{event.device_id, event.name, event.descriptor});
        if (source)
          source->QueueDeviceConnected(event.device_id, std::move(event.name), std::move(event.descriptor));
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

void AndroidHost::AddControllerToPort1(const std::string& identifier)
{
  if (!identifier.starts_with(DEVICE_PREFIX))
    return;

  const std::optional<u32> index = StringUtil::FromChars<u32>(std::string_view(identifier).substr(DEVICE_PREFIX.size()));
  if (!index.has_value())
    return;

  // Mapped later than it connected: skip it if it's gone again.
  const auto devices = InputManager::EnumerateDevices();
  if (std::none_of(devices.begin(), devices.end(),
                   [&identifier](const auto& device) { return device.first == identifier; }))
  {
    return;
  }

  // Every controller drives Port 1: its default bindings are added to the ones there, so a pad connected later (a
  // Bluetooth pad next to a handheld's own) works too. One that connects again adds nothing, as its bindings are there.
  u32 added = 0;
  {
    const auto lock = Host::GetSettingsLock();
    SettingsInterface& si = *s_base_settings_interface;
    const std::string section = Controller::GetSettingsSection(0);
    const std::string type = si.GetStringValue(section.c_str(), "Type", Controller::GetDefaultPadType(0));
    const Controller::ControllerInfo* info = Controller::GetControllerInfo(type);
    if (!info)
    {
      WARNING_LOG("Port 1 has an unknown controller type '{}', not mapping {} to it.", type, identifier);
      return;
    }

    // As InputManager::MapController(): the first binding string for each of the pad's inputs.
    const GenericInputBindingMapping mapping = AndroidInputKeys::GetDefaultMapping(index.value());
    for (const Controller::ControllerBindingInfo& bi : info->bindings)
    {
      if (bi.generic_mapping == GenericInputBinding::Unknown)
        continue;

      const auto it = std::find_if(mapping.begin(), mapping.end(),
                                   [&bi](const auto& entry) { return entry.first == bi.generic_mapping; });
      if (it != mapping.end() && si.AddToStringList(section.c_str(), bi.name, it->second.c_str()))
        added++;
    }

    if (added == 0)
      return;

    INFO_LOG("Added {} bindings for {} to Port 1.", added, identifier);
    Error error;
    if (!si.Save(&error))
      ERROR_LOG("Failed to save settings: {}", error.GetDescription());
  }

  System::ReloadInputBindings();
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
  // The source reports this from inside its poll: map after it, not while the bindings are in use.
  Host::RunOnCPUThread(
    [identifier = std::string(identifier)]() { AndroidHost::AddControllerToPort1(identifier); });
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
