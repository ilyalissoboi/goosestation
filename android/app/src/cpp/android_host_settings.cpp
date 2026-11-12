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

void AndroidHost::ReloadSettings()
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread(
    []() {
      bool adapted;
      bool sync_changed;
      {
        const auto lock = Host::GetSettingsLock();
        INISettingsInterface& si = *s_base_settings_interface;
        const MemcardSyncSettings sync_before = ReadMemcardSyncSettings(si);
        Error error;
        if (!si.Reload(&error))
        {
          ERROR_LOG("Failed to reload the settings from {}, keeping them: {}", si.GetFileName(),
                    error.GetDescription());
          return;
        }

        INFO_LOG("Reloaded the settings from {}.", si.GetFileName());
        adapted = AdaptForeignSettings(si, s_install_id, s_device_name);

        // The indices in use stay as they are, whatever the file has: an adapted one has none.
        bool changed = MoveAnalogBindings(si) || adapted;
        if (si.GetStringList(DESCRIPTORS_SECTION, DESCRIPTORS_KEY) != s_descriptors)
        {
          WriteDescriptors(si);
          changed = true;
        }

        if (changed && !si.Save(&error))
          ERROR_LOG("Failed to save settings: {}", error.GetDescription());
        sync_changed = ReadMemcardSyncSettings(si) != sync_before;
      }

      // Memory card sync's settings came with the file: sync follows them.
      if (sync_changed)
        MemcardSync::ApplySettingsAsync();

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
          SettingsInterface& si = *s_base_settings_interface;
          bound = HasDeviceBinding(si, index.value(), GetPadOfIndex(si, index.value()));
        }

        if (!bound)
          AddControllerToPort(device.first);
      }

      // The file can put controllers on other ports.
      UpdateSourceControllers();

      // Update() takes the lock itself, and loads the folders again. The app's BIOS folder goes back under it too, as
      // other threads read the folders under it (a reader in between sees settings.ini's for a moment).
      EmuFolders::Update();
      {
        const auto lock = Host::GetSettingsLock();
        EmuFolders::Bios = s_bios_dir;
      }
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

float AndroidHost::GetFloatSetting(const char* section, const char* key, float default_value)
{
  if (!s_initialized)
    return default_value;

  const auto lock = Host::GetSettingsLock();
  return s_base_settings_interface->GetFloatValue(section, key, default_value);
}

void AndroidHost::SetFloatSetting(const char* section, const char* key, float value)
{
  if (!s_initialized)
    return;

  const auto lock = Host::GetSettingsLock();
  s_base_settings_interface->SetFloatValue(section, key, value);

  Error error;
  if (!s_base_settings_interface->Save(&error))
    ERROR_LOG("Failed to save settings: {}", error.GetDescription());
}

std::vector<std::string> AndroidHost::GetSharedCardNames()
{
  std::vector<std::string> names;
  if (!s_initialized)
    return names;

  // Under the lock, as EmuFolders::Update() rewrites it under it on the CPU thread.
  std::string folder;
  {
    const auto lock = Host::GetSettingsLock();
    folder = EmuFolders::MemoryCards;
  }
  FileSystem::FindResultsArray results;
  FileSystem::FindFiles(folder.c_str(), "*.mcd",
                        FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_HIDDEN_FILES | FILESYSTEM_FIND_RELATIVE_PATHS,
                        &results);
  names.reserve(results.size());
  for (FILESYSTEM_FIND_DATA& result : results)
    names.push_back(std::move(result.FileName));
  std::sort(names.begin(), names.end());
  return names;
}

std::pair<u64, u64> AndroidHost::GetRewindMemoryUsage(u32 num_saves)
{
  if (!s_initialized)
    return {0, 0};

  // The core reads the resolution and multisampling from g_settings, which the CPU thread owns.
  return EmuThread::Call(std::pair<u64, u64>{0, 0}, [num_saves]() {
    std::pair<u64, u64> usage{0, 0};
    System::CalculateRewindMemoryUsage(num_saves, 0, &usage.first, &usage.second);
    return usage;
  });
}

void AndroidHost::ApplySettings()
{
  if (!s_initialized)
    return;

  EmuThread::RunOnThread([]() { System::ApplySettings(false); }, true);
}

float AndroidHost::GetSurfaceRefreshRateForTest()
{
  float rate = 0.0f;
  EmuThread::RunOnThread(
    [&rate]() {
      if (g_gpu_device)
        rate = g_gpu_device->GetWindowInfo().surface_refresh_rate;
    },
    true);
  return rate;
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
