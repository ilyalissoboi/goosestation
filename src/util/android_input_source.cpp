// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_input_source.h"
#include "input_manager.h"

#include "common/log.h"
#include "common/string_util.h"

#include "fmt/format.h"

#include <android/input.h>
#include <android/keycodes.h>

#include <algorithm>
#include <utility>

Log_SetChannel(AndroidInputSource);

// android_input_keys.h writes Android's numbers out, so that it builds without the NDK. Check them against the NDK.
static_assert(AndroidInputKeys::KEYCODE_BACK == AKEYCODE_BACK);
static_assert(AndroidInputKeys::KEYCODE_DPAD_UP == AKEYCODE_DPAD_UP);
static_assert(AndroidInputKeys::KEYCODE_DPAD_DOWN == AKEYCODE_DPAD_DOWN);
static_assert(AndroidInputKeys::KEYCODE_DPAD_LEFT == AKEYCODE_DPAD_LEFT);
static_assert(AndroidInputKeys::KEYCODE_DPAD_RIGHT == AKEYCODE_DPAD_RIGHT);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_A == AKEYCODE_BUTTON_A);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_B == AKEYCODE_BUTTON_B);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_X == AKEYCODE_BUTTON_X);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_Y == AKEYCODE_BUTTON_Y);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_L1 == AKEYCODE_BUTTON_L1);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_R1 == AKEYCODE_BUTTON_R1);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_L2 == AKEYCODE_BUTTON_L2);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_R2 == AKEYCODE_BUTTON_R2);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_THUMBL == AKEYCODE_BUTTON_THUMBL);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_THUMBR == AKEYCODE_BUTTON_THUMBR);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_START == AKEYCODE_BUTTON_START);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_SELECT == AKEYCODE_BUTTON_SELECT);
static_assert(AndroidInputKeys::KEYCODE_BUTTON_MODE == AKEYCODE_BUTTON_MODE);
static_assert(AndroidInputKeys::AXIS_X == AMOTION_EVENT_AXIS_X);
static_assert(AndroidInputKeys::AXIS_Y == AMOTION_EVENT_AXIS_Y);
static_assert(AndroidInputKeys::AXIS_Z == AMOTION_EVENT_AXIS_Z);
static_assert(AndroidInputKeys::AXIS_RZ == AMOTION_EVENT_AXIS_RZ);
static_assert(AndroidInputKeys::AXIS_HAT_X == AMOTION_EVENT_AXIS_HAT_X);
static_assert(AndroidInputKeys::AXIS_HAT_Y == AMOTION_EVENT_AXIS_HAT_Y);
static_assert(AndroidInputKeys::AXIS_LTRIGGER == AMOTION_EVENT_AXIS_LTRIGGER);
static_assert(AndroidInputKeys::AXIS_RTRIGGER == AMOTION_EVENT_AXIS_RTRIGGER);
static_assert(AndroidInputKeys::AXIS_GAS == AMOTION_EVENT_AXIS_GAS);
static_assert(AndroidInputKeys::AXIS_BRAKE == AMOTION_EVENT_AXIS_BRAKE);

static constexpr std::string_view DEVICE_PREFIX = "Android-";

static std::optional<u32> ParseDeviceIndex(std::string_view device)
{
  if (!device.starts_with(DEVICE_PREFIX))
    return std::nullopt;

  return StringUtil::FromChars<u32>(device.substr(DEVICE_PREFIX.size()));
}

AndroidInputSource::AndroidInputSource() = default;

AndroidInputSource::~AndroidInputSource() = default;

bool AndroidInputSource::Initialize(SettingsInterface& si, std::unique_lock<std::mutex>& settings_lock)
{
  return true;
}

void AndroidInputSource::UpdateSettings(SettingsInterface& si, std::unique_lock<std::mutex>& settings_lock)
{
}

bool AndroidInputSource::ReloadDevices()
{
  // The app reports connections as they happen.
  return false;
}

void AndroidInputSource::Shutdown()
{
  {
    std::unique_lock lock(m_queue_mutex);
    m_queue.clear();
  }

  m_devices.clear();
}

void AndroidInputSource::QueueEvent(QueuedEvent event)
{
  std::unique_lock lock(m_queue_mutex);
  m_queue.push_back(std::move(event));
}

void AndroidInputSource::QueueKeyEvent(s32 device_id, s32 keycode, bool pressed)
{
  QueueEvent(QueuedEvent{QueuedEvent::Type::Key, device_id, keycode, 0, pressed ? 1.0f : 0.0f, {}});
}

void AndroidInputSource::QueueAxisEvent(s32 device_id, s32 axis, float value)
{
  QueueEvent(QueuedEvent{QueuedEvent::Type::Axis, device_id, axis, 0, value, {}});
}

void AndroidInputSource::QueueDeviceConnected(s32 device_id, u32 index, std::string name)
{
  QueueEvent(QueuedEvent{QueuedEvent::Type::Connected, device_id, 0, index, 0.0f, std::move(name)});
}

void AndroidInputSource::QueueDeviceDisconnected(s32 device_id)
{
  QueueEvent(QueuedEvent{QueuedEvent::Type::Disconnected, device_id, 0, 0, 0.0f, {}});
}

void AndroidInputSource::QueueReleaseAll()
{
  QueueEvent(QueuedEvent{QueuedEvent::Type::ReleaseAll, 0, 0, 0, 0.0f, {}});
}

void AndroidInputSource::PollEvents()
{
  {
    std::unique_lock lock(m_queue_mutex);
    if (m_queue.empty())
      return;

    m_polled.swap(m_queue);
  }

  for (QueuedEvent& event : m_polled)
  {
    switch (event.type)
    {
      case QueuedEvent::Type::Key:
      case QueuedEvent::Type::Axis:
      {
        Device* device = GetDeviceById(event.device_id);
        if (!device)
          break;

        m_pad_events.clear();
        if (event.type == QueuedEvent::Type::Key)
          device->state.OnKey(event.code, event.value != 0.0f, m_pad_events);
        else
          device->state.OnAxis(event.code, event.value, m_pad_events);
        ReportPadEvents(device->index, m_pad_events);
      }
      break;

      case QueuedEvent::Type::Connected:
        HandleConnected(event.device_id, event.index, std::move(event.name));
        break;

      case QueuedEvent::Type::Disconnected:
        HandleDisconnected(event.device_id);
        break;

      case QueuedEvent::Type::ReleaseAll:
      {
        for (Device& device : m_devices)
          ReleaseAll(device);
      }
      break;
    }
  }

  m_polled.clear();
}

AndroidInputSource::Device* AndroidInputSource::GetDeviceById(s32 device_id)
{
  const auto it =
    std::find_if(m_devices.begin(), m_devices.end(), [device_id](const Device& d) { return d.device_id == device_id; });
  return (it != m_devices.end()) ? &*it : nullptr;
}

bool AndroidInputSource::IsIndexConnected(u32 index) const
{
  return std::any_of(m_devices.begin(), m_devices.end(), [index](const Device& d) { return d.index == index; });
}

void AndroidInputSource::HandleConnected(s32 device_id, u32 index, std::string name)
{
  if (GetDeviceById(device_id))
    return;

  if (index > 0xFFu)
  {
    // InputBindingKey has 8 bits for it.
    ERROR_LOG("Too many controllers seen, ignoring '{}'.", name);
    return;
  }

  const std::string identifier = fmt::format("Android-{}", index);
  m_devices.push_back(Device{device_id, index, name, {}});
  InputManager::OnInputDeviceConnected(identifier, name);
}

void AndroidInputSource::HandleDisconnected(s32 device_id)
{
  const auto it =
    std::find_if(m_devices.begin(), m_devices.end(), [device_id](const Device& d) { return d.device_id == device_id; });
  if (it == m_devices.end())
    return;

  // Let go of everything it held, so nothing stays pressed.
  ReleaseAll(*it);

  const u32 index = it->index;
  m_devices.erase(it);

  InputBindingKey key = {};
  key.source_type = InputSourceType::Android;
  key.source_index = index;
  InputManager::OnInputDeviceDisconnected(key, fmt::format("Android-{}", index));
}

void AndroidInputSource::ReleaseAll(Device& device)
{
  m_pad_events.clear();
  device.state.ReleaseAll(m_pad_events);
  ReportPadEvents(device.index, m_pad_events);
}

void AndroidInputSource::ReportPadEvents(u32 index, const std::vector<AndroidInputKeys::PadEvent>& events)
{
  for (const AndroidInputKeys::PadEvent& event : events)
  {
    const InputBindingKey key =
      (event.subclass == InputSubclass::ControllerAxis) ?
        MakeGenericControllerAxisKey(InputSourceType::Android, index, static_cast<s32>(event.number)) :
        MakeGenericControllerButtonKey(InputSourceType::Android, index, static_cast<s32>(event.number));
    InputManager::InvokeEvents(key, event.value, event.binding);
  }
}

std::optional<InputBindingKey> AndroidInputSource::ParseKeyString(std::string_view device, std::string_view binding)
{
  if (!ParseDeviceIndex(device).has_value())
    return std::nullopt;

  return ParseGenericControllerKey(InputSourceType::Android, device, binding);
}

TinyString AndroidInputSource::ConvertKeyToString(InputBindingKey key)
{
  if (key.source_type != InputSourceType::Android)
    return {};

  return TinyString(std::string_view(ConvertGenericControllerKeyToString(key)));
}

TinyString AndroidInputSource::ConvertKeyToIcon(InputBindingKey key)
{
  return {};
}

std::vector<std::pair<std::string, std::string>> AndroidInputSource::EnumerateDevices()
{
  std::vector<std::pair<std::string, std::string>> ret;
  for (const Device& device : m_devices)
    ret.emplace_back(fmt::format("Android-{}", device.index), device.name);
  return ret;
}

std::vector<InputBindingKey> AndroidInputSource::EnumerateMotors()
{
  // No vibration yet.
  return {};
}

bool AndroidInputSource::GetGenericBindingMapping(std::string_view device, GenericInputBindingMapping* mapping)
{
  const std::optional<u32> index = ParseDeviceIndex(device);
  if (!index.has_value() || !IsIndexConnected(index.value()))
    return false;

  GenericInputBindingMapping device_mapping = AndroidInputKeys::GetDefaultMapping(index.value());
  mapping->insert(mapping->end(), std::make_move_iterator(device_mapping.begin()),
                  std::make_move_iterator(device_mapping.end()));
  return true;
}

void AndroidInputSource::UpdateMotorState(InputBindingKey key, float intensity)
{
}

std::unique_ptr<InputSource> InputSource::CreateAndroidSource()
{
  return std::make_unique<AndroidInputSource>();
}
