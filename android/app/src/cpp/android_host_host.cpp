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
