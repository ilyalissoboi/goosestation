// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// No-op frontend for linking the core library into common-tests. The core calls these functions on its host (the Qt
// app, the Android app); tests that use the core's pure parts, such as Settings::Load, never reach them. Resources
// aren't found, so ImGuiFullscreen's textures fail to load without a GPU device.

#include "core/achievements.h"
#include "core/fullscreen_ui.h"
#include "core/game_list.h"
#include "core/host.h"
#include "core/memcard_sync.h"
#include "core/system.h"

#include "util/gpu_device.h"
#include "util/host.h"
#include "util/http_downloader.h"
#include "util/imgui_fullscreen.h"
#include "util/imgui_manager.h"
#include "util/input_manager.h"

#include "common/error.h"
#include "common/heap_array.h"
#include "common/small_string.h"

#include <functional>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>

// Resources, translation and messages.

bool Host::ResourceFileExists(std::string_view filename, bool allow_override)
{
  return false;
}

std::optional<DynamicHeapArray<u8>> Host::ReadResourceFile(std::string_view filename, bool allow_override)
{
  return std::nullopt;
}

std::optional<std::string> Host::ReadResourceFileToString(std::string_view filename, bool allow_override)
{
  return std::nullopt;
}

std::optional<std::time_t> Host::GetResourceFileTimestamp(std::string_view filename, bool allow_override)
{
  return std::nullopt;
}

s32 Host::Internal::GetTranslatedStringImpl(std::string_view context, std::string_view msg, char* tbuf,
                                            size_t tbuf_space)
{
  if (msg.size() > tbuf_space)
    return -1;
  std::copy(msg.begin(), msg.end(), tbuf);
  return static_cast<s32>(msg.size());
}

std::string Host::TranslatePluralToString(const char* context, const char* msg, const char* disambiguation, int count)
{
  return msg;
}

SmallString Host::TranslatePluralToSmallString(const char* context, const char* msg, const char* disambiguation,
                                               int count)
{
  return SmallString(msg);
}

std::span<const std::pair<const char*, const char*>> Host::GetAvailableLanguageList()
{
  return {};
}

bool Host::ChangeLanguage(const char* new_language)
{
  return false;
}

void Host::ReportErrorAsync(std::string_view title, std::string_view message)
{
}

bool Host::ConfirmMessage(std::string_view title, std::string_view message)
{
  return false;
}

void Host::ReportDebuggerMessage(std::string_view message)
{
}

void Host::OpenURL(std::string_view url)
{
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

// Settings and the CPU thread.

void Host::LoadSettings(SettingsInterface& si, std::unique_lock<std::mutex>& lock)
{
}

void Host::CheckForSettingsChanges(const Settings& old_settings)
{
}

void Host::CommitBaseSettingChanges()
{
}

void Host::RunOnCPUThread(std::function<void()> function, bool block)
{
}

void Host::PumpMessagesOnCPUThread()
{
}

void Host::RefreshGameListAsync(bool invalidate_cache)
{
}

// System lifecycle.

void Host::OnSystemStarting()
{
}

void Host::OnSystemStarted()
{
}

void Host::OnSystemDestroyed()
{
}

void Host::OnSystemPaused()
{
}

void Host::OnSystemResumed()
{
}

void Host::OnIdleStateChanged()
{
}

void Host::OnPerformanceCountersUpdated()
{
}

void Host::OnGameChanged(const std::string& disc_path, const std::string& game_serial, const std::string& game_name)
{
}

void Host::OnMediaCaptureStarted()
{
}

void Host::OnMediaCaptureStopped()
{
}

void Host::RequestSystemShutdown(bool allow_confirm, bool save_state)
{
}

void Host::RequestExitApplication(bool allow_confirm)
{
}

void Host::RequestExitBigPicture()
{
}

void Host::OnCoverDownloaderOpenRequested()
{
}

// Display.

std::optional<WindowInfo> Host::AcquireRenderWindow(bool recreate_window)
{
  // Surfaceless, so tests can create a GPU device and render offscreen.
  return WindowInfo();
}

void Host::ReleaseRenderWindow()
{
}

bool Host::IsFullscreen()
{
  return false;
}

void Host::SetFullscreen(bool enabled)
{
}

void Host::RequestResizeHostDisplay(s32 width, s32 height)
{
}

void Host::FrameDone()
{
}

bool Host::ShouldPreferHostFileSelector()
{
  return false;
}

void Host::OpenHostFileSelectorAsync(std::string_view title, bool select_directory, FileSelectorCallback callback,
                                     FileSelectorFilters filters, std::string_view initial_directory)
{
}

// Input.

void Host::AddFixedInputBindings(SettingsInterface& si)
{
}

void Host::OnInputDeviceConnected(std::string_view identifier, std::string_view device_name)
{
}

void Host::OnInputDeviceDisconnected(InputBindingKey key, std::string_view identifier)
{
}

void Host::SetMouseMode(bool relative, bool hide_cursor)
{
}

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

BEGIN_HOTKEY_LIST(g_host_hotkeys)
END_HOTKEY_LIST()

// Achievements.

void Host::OnAchievementsLoginRequested(Achievements::LoginRequestReason reason)
{
}

void Host::OnAchievementsLoginSuccess(const char* display_name, u32 points, u32 sc_points, u32 unread_messages)
{
}

void Host::OnAchievementsRefreshed()
{
}

void Host::OnAchievementsHardcoreModeChanged(bool enabled)
{
}

#ifdef __ANDROID__

// What the Android app provides on top (android/app/src/cpp).

std::unique_ptr<HTTPDownloader> HTTPDownloader::Create(std::string user_agent)
{
  return {};
}

bool Host::OnVulkanDeviceFailed(const Error& error)
{
  return false;
}

std::optional<Host::SaveStateMedia> Host::ResolveSaveStateMedia(std::string_view serial, std::string_view media_path,
                                                                u32 subimage_index, Error* error)
{
  return std::nullopt;
}

void Host::OnOSDMessagePosted(std::string_view key, std::string_view message, float duration, const OSDImage* image)
{
}

void Host::OnMemcardSyncChanged()
{
}

void Host::OnAchievementNotification(std::string_view key, float seconds, std::string_view title,
                                     std::string_view text, std::string_view image_path)
{
}

void Host::OnAchievementToast(std::string_view title, std::string_view message, float seconds)
{
}

void Host::OnAchievementIndicator(AchievementIndicator kind, u32 id, bool shown, std::string_view text,
                                  std::string_view badge_path)
{
}

void Host::OnAchievementImageDownloaded(std::string_view path)
{
}

void Host::OnAchievementsLoginExpired()
{
}

#endif
