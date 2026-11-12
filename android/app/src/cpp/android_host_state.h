// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

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

namespace AndroidHost {

using AndroidInputKeys::DEVICE_PREFIX;

using AndroidInputKeys::ParseDeviceIndex;

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

void WriteDescriptors(SettingsInterface& si);
u32 GetPadOfIndex(SettingsInterface& si, u32 index);

u32 AddDefaultBindings(SettingsInterface& si, u32 index, u32 pad);
u32 AddDefaultBindings(SettingsInterface& si, u32 index, u32 pad, const Controller::ControllerInfo* info);

void AddRunningTypesDefaults();
bool HasDeviceBinding(SettingsInterface& si, u32 index, u32 pad);
bool AddControllerToPort(const std::string& identifier);
void UpdateSourceControllers();

void PostUnusedPortMessages();
void MapNewController(const std::string& identifier);
bool IsVulkanRenderer(GPURenderer renderer);

bool MoveAnalogBindings(SettingsInterface& si);
void PressAnalogButton();

// Where each controller's device index (Android-<index>) is kept, by its descriptor.
static constexpr const char* DESCRIPTORS_SECTION = AndroidInputKeys::SETTINGS_SECTION;
static constexpr const char* DESCRIPTORS_KEY = AndroidInputKeys::DESCRIPTORS_KEY;
static constexpr const char* PORTS_KEY = AndroidInputKeys::PORTS_KEY;
static constexpr const char* RUMBLE_KEY = AndroidInputKeys::RUMBLE_KEY;

// The core's log in the data root (Settings::UpdateLogSettings), and the last run's, which the host keeps.
static constexpr const char* LOG_FILE = "goosestation.log";
static constexpr const char* PREVIOUS_LOG_FILE = "goosestation-previous.log";

} // namespace AndroidHost

std::optional<ExtendedSaveStateInfo> ReadStateInfo(const std::string& path);

namespace AndroidHost {

extern std::atomic_bool s_initialized;

extern std::unique_ptr<INISettingsInterface> s_base_settings_interface;

extern std::string s_bios_dir;

extern std::string s_install_id;

extern std::string s_device_name;

extern std::mutex s_input_mutex;

extern std::vector<InputEvent> s_input_queue;

extern std::vector<s32> s_queued_device_ids;

extern bool s_accept_input_events;

extern std::optional<TouchPad::State> s_touch_queued;

extern u32 s_touch_tapped;

extern std::vector<std::string> s_descriptors;

extern std::vector<InputEvent> s_input_events;

extern std::vector<ConnectedDevice> s_connected_devices;

extern std::vector<u32> s_unmapped_indices;

extern AndroidInputSource* s_input_source;

extern ANativeWindow* s_window;

extern u32 s_window_width;

extern u32 s_window_height;

extern float s_window_scale;

extern float s_window_refresh_rate;

extern std::optional<std::string> s_pending_boot_path;

extern AndroidDiscGroups::Game s_pending_game;

extern u32 s_pending_playlist_index;

extern std::optional<AndroidDiscGroups::Game> s_running_game;

extern std::mutex s_listed_games_mutex;

extern std::vector<AndroidDiscGroups::Game> s_listed_games;

extern std::string s_pending_boot_state;

extern std::string s_boot_path;

extern bool s_game_started;

extern bool s_fastmem_override_logged;

extern bool s_fast_forward_reported;

extern TouchPad::State s_touch_state;

extern TouchPad::State s_touch_applied;

extern std::atomic<u32> s_touch_presses_for_test;

extern s32 s_pad_kind_reported;

extern bool s_picture_at_top;

extern DisplayAlignment s_loaded_display_alignment;

extern u32 s_binding_capture_id;

extern std::atomic_bool s_vulkan_failed;

} // namespace AndroidHost

std::string MissingDiscMessage(std::string_view media_path);
