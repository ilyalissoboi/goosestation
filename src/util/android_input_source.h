// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "android_input_keys.h"
#include "input_source.h"

#include <mutex>
#include <string>
#include <vector>

/// Controllers on Android. The app passes their events in through the Queue functions, from any thread; PollEvents()
/// reports them to the InputManager on the CPU thread. Everything else runs on the CPU thread too.
///
/// Devices are named Android-<index>. The host gives the index, which belongs to the device's descriptor, so a
/// controller that reconnects (and gets a new device ID from Android) keeps its bindings. Two devices with the same
/// descriptor share an index. Bindings are Button<keycode>, +Axis<n>, -Axis<n> and FullAxis<n>, with Android's keycode
/// and axis numbers.
class AndroidInputSource final : public InputSource
{
public:
  AndroidInputSource();
  ~AndroidInputSource() override;

  bool Initialize(SettingsInterface& si, std::unique_lock<std::mutex>& settings_lock) override;
  void UpdateSettings(SettingsInterface& si, std::unique_lock<std::mutex>& settings_lock) override;
  bool ReloadDevices() override;
  void Shutdown() override;

  void PollEvents() override;

  std::optional<InputBindingKey> ParseKeyString(std::string_view device, std::string_view binding) override;
  TinyString ConvertKeyToString(InputBindingKey key) override;
  TinyString ConvertKeyToIcon(InputBindingKey key) override;

  std::vector<std::pair<std::string, std::string>> EnumerateDevices() override;
  std::vector<InputBindingKey> EnumerateMotors() override;
  bool GetGenericBindingMapping(std::string_view device, GenericInputBindingMapping* mapping) override;
  void UpdateMotorState(InputBindingKey key, float intensity) override;

  void QueueKeyEvent(s32 device_id, s32 keycode, bool pressed);
  void QueueAxisEvent(s32 device_id, s32 axis, float value);
  void QueueDeviceConnected(s32 device_id, u32 index, std::string name);
  void QueueDeviceDisconnected(s32 device_id);

  /// Lets go of everything every device holds, after the events queued before it.
  void QueueReleaseAll();

private:
  struct QueuedEvent
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
    s32 code;  // keycode or axis
    u32 index; // Connected's device index
    float value;
    std::string name;
  };

  struct Device
  {
    s32 device_id;
    u32 index;
    std::string name;
    AndroidInputKeys::PadState state;
  };

  void QueueEvent(QueuedEvent event);
  Device* GetDeviceById(s32 device_id);
  bool IsIndexConnected(u32 index) const;
  void HandleConnected(s32 device_id, u32 index, std::string name);
  void HandleDisconnected(s32 device_id);
  void ReleaseAll(Device& device);
  void ReportPadEvents(u32 index, const std::vector<AndroidInputKeys::PadEvent>& events);

  std::mutex m_queue_mutex;
  std::vector<QueuedEvent> m_queue;

  // CPU thread only.
  std::vector<QueuedEvent> m_polled;
  std::vector<AndroidInputKeys::PadEvent> m_pad_events;
  std::vector<Device> m_devices;
};
