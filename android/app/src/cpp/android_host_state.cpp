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

namespace AndroidHost {

using AndroidInputKeys::DEVICE_PREFIX;

using AndroidInputKeys::ParseDeviceIndex;

std::atomic_bool s_initialized{false};

std::unique_ptr<INISettingsInterface> s_base_settings_interface;

// Set once by Initialize(), before s_initialized.
std::string s_bios_dir;
std::string s_install_id;
std::string s_device_name; // memory card sync's default DeviceName

// Input queued by the app's threads.
std::mutex s_input_mutex;
std::vector<InputEvent> s_input_queue;
std::vector<s32> s_queued_device_ids; // connected as far as the queue goes, for QueueKeyEvent()'s result
bool s_accept_input_events = false;   // key and axis events are dropped until the CPU thread runs
std::optional<TouchPad::State> s_touch_queued; // the touch pad's latest state, until the CPU thread takes it
u32 s_touch_tapped = 0; // buttons a replaced queued state pressed and the latest doesn't: a tap not yet seen

// Each controller's descriptor, at its device index: [Android] ControllerDescriptors. It's only ever appended to, so a
// controller's index never changes in this process, and its bindings stay its own. InitializeConfig() seeds it before
// the CPU thread starts; only the CPU thread touches it after that.
std::vector<std::string> s_descriptors;

// CPU thread only.
std::vector<InputEvent> s_input_events;
std::vector<ConnectedDevice> s_connected_devices;
std::vector<u32> s_unmapped_indices; // new descriptors' indices, whose defaults aren't on Port 1 yet
AndroidInputSource* s_input_source = nullptr;
ANativeWindow* s_window = nullptr;
u32 s_window_width = 0;
u32 s_window_height = 0;
float s_window_scale = 1.0f;
float s_window_refresh_rate = 0.0f; // the screen's, in Hz; 0 when the app doesn't know it
std::optional<std::string> s_pending_boot_path;
AndroidDiscGroups::Game s_pending_game;     // the game the pending boot is for
u32 s_pending_playlist_index = 0;          // the pending boot's sub-image, for a playlist
std::optional<AndroidDiscGroups::Game> s_running_game; // from its boot to its end, CPU thread
std::mutex s_listed_games_mutex;
std::vector<AndroidDiscGroups::Game> s_listed_games; // guarded by s_listed_games_mutex
std::string s_pending_boot_state; // the save state the pending boot starts from, if any
std::string s_boot_path;
bool s_game_started = false;
bool s_fastmem_override_logged = false;
bool s_fast_forward_reported = false; // as the app was last told, CPU thread
TouchPad::State s_touch_state;   // what the touch pad holds, as far as the CPU thread has it
TouchPad::State s_touch_applied; // what Port 1's controller was last given of it
std::atomic<u32> s_touch_presses_for_test{0}; // the buttons given as pressed, for TakeTouchPressesForTest()
s32 s_pad_kind_reported = -1;    // Port 1's kind, as the app was last told, CPU thread
bool s_picture_at_top = false;   // SetPictureAtTop(), CPU thread
DisplayAlignment s_loaded_display_alignment = Settings::DEFAULT_DISPLAY_ALIGNMENT; // the setting's own value
u32 s_binding_capture_id = 0; // changes when a capture starts or is cancelled, so a stale report is dropped

// Set when the Vulkan device didn't start: games use OpenGL ES until the process ends.
std::atomic_bool s_vulkan_failed{false};

} // namespace AndroidHost
