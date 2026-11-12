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

static void QueueInputEvent(InputEvent event);

static void SetWindow(ANativeWindow* window, s32 width, s32 height, float scale, float refresh_rate);

static void ApplyTouchPad();

static std::vector<InputEvent> s_input_queue;

static std::vector<s32> s_queued_device_ids; // connected as far as the queue goes, for QueueKeyEvent()'s result

// CPU thread only.
static std::vector<InputEvent> s_input_events;

static AndroidInputSource* s_input_source = nullptr;

static std::atomic<u32> s_touch_presses_for_test{0}; // the buttons given as pressed, for TakeTouchPressesForTest()

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
