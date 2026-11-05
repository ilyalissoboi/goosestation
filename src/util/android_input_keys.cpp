// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_input_keys.h"

#include "common/string_util.h"

#include "fmt/format.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <utility>

namespace AndroidInputKeys {
namespace {

struct KeyBinding
{
  s32 keycode;
  GenericInputBinding binding;
};

constexpr std::array KEY_BINDINGS = {
  KeyBinding{KEYCODE_DPAD_UP, GenericInputBinding::DPadUp},
  KeyBinding{KEYCODE_DPAD_DOWN, GenericInputBinding::DPadDown},
  KeyBinding{KEYCODE_DPAD_LEFT, GenericInputBinding::DPadLeft},
  KeyBinding{KEYCODE_DPAD_RIGHT, GenericInputBinding::DPadRight},
  KeyBinding{KEYCODE_BUTTON_A, GenericInputBinding::Cross},
  KeyBinding{KEYCODE_BUTTON_B, GenericInputBinding::Circle},
  KeyBinding{KEYCODE_BUTTON_X, GenericInputBinding::Square},
  KeyBinding{KEYCODE_BUTTON_Y, GenericInputBinding::Triangle},
  KeyBinding{KEYCODE_BUTTON_L1, GenericInputBinding::L1},
  KeyBinding{KEYCODE_BUTTON_R1, GenericInputBinding::R1},
  KeyBinding{KEYCODE_BUTTON_L2, GenericInputBinding::L2},
  KeyBinding{KEYCODE_BUTTON_R2, GenericInputBinding::R2},
  KeyBinding{KEYCODE_BUTTON_THUMBL, GenericInputBinding::L3},
  KeyBinding{KEYCODE_BUTTON_THUMBR, GenericInputBinding::R3},
  KeyBinding{KEYCODE_BUTTON_START, GenericInputBinding::Start},
  KeyBinding{KEYCODE_BUTTON_SELECT, GenericInputBinding::Select},
};

struct AxisBinding
{
  s32 axis;
  GenericInputBinding negative;
  GenericInputBinding positive;
};

// Android's Y axes point down.
constexpr std::array AXIS_BINDINGS = {
  AxisBinding{AXIS_X, GenericInputBinding::LeftStickLeft, GenericInputBinding::LeftStickRight},
  AxisBinding{AXIS_Y, GenericInputBinding::LeftStickUp, GenericInputBinding::LeftStickDown},
  AxisBinding{AXIS_Z, GenericInputBinding::RightStickLeft, GenericInputBinding::RightStickRight},
  AxisBinding{AXIS_RZ, GenericInputBinding::RightStickUp, GenericInputBinding::RightStickDown},
  AxisBinding{AXIS_LTRIGGER, GenericInputBinding::Unknown, GenericInputBinding::L2},
  AxisBinding{AXIS_RTRIGGER, GenericInputBinding::Unknown, GenericInputBinding::R2},
  AxisBinding{AXIS_BRAKE, GenericInputBinding::Unknown, GenericInputBinding::L2},
  AxisBinding{AXIS_GAS, GenericInputBinding::Unknown, GenericInputBinding::R2},
  AxisBinding{AXIS_HAT_X, GenericInputBinding::DPadLeft, GenericInputBinding::DPadRight},
  AxisBinding{AXIS_HAT_Y, GenericInputBinding::DPadUp, GenericInputBinding::DPadDown},
};

/// A trigger comes as a key and as two axes; pads report any of them, or several.
struct Trigger
{
  s32 keycode;
  s32 axis;
  s32 other_axis;
  GenericInputBinding binding;
};

constexpr std::array TRIGGERS = {
  Trigger{KEYCODE_BUTTON_L2, AXIS_LTRIGGER, AXIS_BRAKE, GenericInputBinding::L2},
  Trigger{KEYCODE_BUTTON_R2, AXIS_RTRIGGER, AXIS_GAS, GenericInputBinding::R2},
};

/// A d-pad direction comes as a key, or as one side of a hat axis.
struct DPadDirection
{
  s32 keycode;
  s32 hat_axis;
  bool positive;
  GenericInputBinding binding;
};

constexpr std::array DPAD_DIRECTIONS = {
  DPadDirection{KEYCODE_DPAD_UP, AXIS_HAT_Y, false, GenericInputBinding::DPadUp},
  DPadDirection{KEYCODE_DPAD_DOWN, AXIS_HAT_Y, true, GenericInputBinding::DPadDown},
  DPadDirection{KEYCODE_DPAD_LEFT, AXIS_HAT_X, false, GenericInputBinding::DPadLeft},
  DPadDirection{KEYCODE_DPAD_RIGHT, AXIS_HAT_X, true, GenericInputBinding::DPadRight},
};

// Hats report -1, 0 or 1; anything past half way counts as pressed.
constexpr float HAT_THRESHOLD = 0.5f;

// BindingCapture's: how far an input must go to be taken, and how near 0 an axis must be seen first.
constexpr float CAPTURE_THRESHOLD = 0.5f;
constexpr float CAPTURE_CENTER = 0.25f;

const Trigger* FindTriggerForKey(s32 keycode)
{
  const auto it =
    std::find_if(TRIGGERS.begin(), TRIGGERS.end(), [keycode](const Trigger& t) { return t.keycode == keycode; });
  return (it != TRIGGERS.end()) ? &*it : nullptr;
}

const Trigger* FindTriggerForAxis(s32 axis)
{
  const auto it = std::find_if(TRIGGERS.begin(), TRIGGERS.end(),
                               [axis](const Trigger& t) { return (t.axis == axis || t.other_axis == axis); });
  return (it != TRIGGERS.end()) ? &*it : nullptr;
}

const DPadDirection* FindDPadDirectionForKey(s32 keycode)
{
  const auto it = std::find_if(DPAD_DIRECTIONS.begin(), DPAD_DIRECTIONS.end(),
                               [keycode](const DPadDirection& d) { return d.keycode == keycode; });
  return (it != DPAD_DIRECTIONS.end()) ? &*it : nullptr;
}

bool IsHatAxis(s32 axis)
{
  return (axis == AXIS_HAT_X || axis == AXIS_HAT_Y);
}

} // namespace

std::optional<GenericInputBinding> GetBindingForKeyCode(s32 keycode)
{
  for (const KeyBinding& kb : KEY_BINDINGS)
  {
    if (kb.keycode == keycode)
      return kb.binding;
  }

  return std::nullopt;
}

std::optional<GenericInputBinding> GetBindingForAxis(s32 axis, bool positive)
{
  for (const AxisBinding& ab : AXIS_BINDINGS)
  {
    if (ab.axis != axis)
      continue;

    const GenericInputBinding binding = positive ? ab.positive : ab.negative;
    if (binding == GenericInputBinding::Unknown)
      return std::nullopt;

    return binding;
  }

  return std::nullopt;
}

u32 IndexForDescriptor(std::vector<std::string>& descriptors, std::string_view descriptor, bool* added)
{
  const auto it = std::find(descriptors.begin(), descriptors.end(), descriptor);
  *added = (it == descriptors.end());
  if (!*added)
    return static_cast<u32>(it - descriptors.begin());

  descriptors.emplace_back(descriptor);
  return static_cast<u32>(descriptors.size() - 1);
}

std::optional<std::string> GetDeviceBinding(std::span<const std::string> bindings, u32 index)
{
  // With the '/', so Android-1/ isn't taken for Android-10/'s start.
  const std::string prefix = fmt::format("Android-{}/", index);
  for (const std::string& binding : bindings)
  {
    if (binding.starts_with(prefix))
      return binding.substr(prefix.size());
  }

  return std::nullopt;
}

std::string SelectChord(u32 device_index, std::string_view button)
{
  return fmt::format("Button{} & Android-{}/{}", KEYCODE_BUTTON_SELECT, device_index, button);
}

AnalogBindings MoveAnalogBindings(std::span<const std::string> analog, std::span<const std::string> hotkey)
{
  AnalogBindings result{{}, std::vector<std::string>(hotkey.begin(), hotkey.end()), false};
  for (const std::string& binding : analog)
  {
    const std::string_view prefix = "Android-";
    const std::string::size_type slash = binding.find('/');
    std::optional<u32> index;
    if (binding.starts_with(prefix) && slash != std::string::npos)
      index = StringUtil::FromChars<u32>(std::string_view(binding).substr(prefix.size(), slash - prefix.size()));
    if (!index.has_value())
    {
      result.analog.push_back(binding);
      continue;
    }

    result.changed = true;
    if (GetDeviceBinding(result.hotkey, index.value()).has_value())
      continue;

    const std::string rest = binding.substr(slash + 1);
    result.hotkey.push_back((rest.find('&') != std::string::npos) ? binding :
                                                                     fmt::format("Android-{}/{}", index.value(),
                                                                                 SelectChord(index.value(), rest)));
  }

  return result;
}

std::vector<std::string> SetDeviceBinding(std::span<const std::string> bindings, u32 index, std::string_view binding)
{
  const std::string prefix = fmt::format("Android-{}/", index);
  std::vector<std::string> result;
  result.reserve(bindings.size() + 1);
  for (const std::string& existing : bindings)
  {
    if (!existing.starts_with(prefix))
      result.push_back(existing);
  }

  if (!binding.empty())
    result.push_back(fmt::format("{}{}", prefix, binding));

  return result;
}

GenericInputBindingMapping GetDefaultMapping(u32 device_index)
{
  GenericInputBindingMapping mapping;

  // Axes first, so that MapController binds the analog side of each trigger. The hat isn't bound: it presses the
  // d-pad buttons. Which of a trigger's inputs is bound doesn't matter, as each follows the other two (PadState).
  for (const AxisBinding& ab : AXIS_BINDINGS)
  {
    if (IsHatAxis(ab.axis))
      continue;

    if (ab.negative != GenericInputBinding::Unknown)
      mapping.emplace_back(ab.negative, fmt::format("Android-{}/-Axis{}", device_index, ab.axis));
    if (ab.positive != GenericInputBinding::Unknown)
      mapping.emplace_back(ab.positive, fmt::format("Android-{}/+Axis{}", device_index, ab.axis));
  }

  for (const KeyBinding& kb : KEY_BINDINGS)
    mapping.emplace_back(kb.binding, fmt::format("Android-{}/Button{}", device_index, kb.keycode));

  return mapping;
}

float PadState::GetKey(s32 keycode) const
{
  const auto it = m_keys.find(keycode);
  return (it != m_keys.end() && it->second) ? 1.0f : 0.0f;
}

float PadState::GetAxis(s32 axis) const
{
  const auto it = m_axes.find(axis);
  return (it != m_axes.end()) ? it->second : 0.0f;
}

void PadState::Report(InputSubclass subclass, s32 number, float value, GenericInputBinding binding,
                      std::vector<PadEvent>& events)
{
  const std::pair<InputSubclass, u32> input(subclass, static_cast<u32>(number));
  const auto it = m_reported.find(input);
  if (it == m_reported.end())
  {
    // Inputs start at rest.
    if (value == 0.0f)
      return;

    m_reported.emplace(input, Reported{value, binding});
  }
  else
  {
    if (it->second.value == value)
      return;

    it->second.value = value;
  }

  events.push_back(PadEvent{subclass, static_cast<u32>(number), value, binding});
}

void PadState::OnKey(s32 keycode, bool pressed, std::vector<PadEvent>& events)
{
  if (keycode < 0)
    return;

  m_keys[keycode] = pressed;

  if (const Trigger* trigger = FindTriggerForKey(keycode))
  {
    // The key goes first, so that a binding prompt picks it.
    const float value = std::max({GetKey(trigger->keycode), GetAxis(trigger->axis), GetAxis(trigger->other_axis)});
    Report(InputSubclass::ControllerButton, trigger->keycode, value, trigger->binding, events);
    Report(InputSubclass::ControllerAxis, trigger->axis, value, trigger->binding, events);
    Report(InputSubclass::ControllerAxis, trigger->other_axis, value, trigger->binding, events);
  }
  else if (const DPadDirection* direction = FindDPadDirectionForKey(keycode))
  {
    const float hat = GetAxis(direction->hat_axis);
    const bool hat_pressed = direction->positive ? (hat > HAT_THRESHOLD) : (hat < -HAT_THRESHOLD);
    Report(InputSubclass::ControllerButton, keycode, (pressed || hat_pressed) ? 1.0f : 0.0f, direction->binding,
           events);
  }
  else
  {
    Report(InputSubclass::ControllerButton, keycode, pressed ? 1.0f : 0.0f,
           GetBindingForKeyCode(keycode).value_or(GenericInputBinding::Unknown), events);
  }
}

void PadState::OnAxis(s32 axis, float value, std::vector<PadEvent>& events)
{
  if (axis < 0)
    return;

  m_axes[axis] = value;

  if (const Trigger* trigger = FindTriggerForAxis(axis))
  {
    // The axis that moved goes first, so that a binding prompt picks it.
    const s32 other_axis = (axis == trigger->axis) ? trigger->other_axis : trigger->axis;
    const float merged = std::max({GetKey(trigger->keycode), GetAxis(trigger->axis), GetAxis(trigger->other_axis)});
    Report(InputSubclass::ControllerAxis, axis, merged, trigger->binding, events);
    Report(InputSubclass::ControllerAxis, other_axis, merged, trigger->binding, events);
    Report(InputSubclass::ControllerButton, trigger->keycode, merged, trigger->binding, events);
  }
  else if (IsHatAxis(axis))
  {
    // Releases before presses, so that a hat going straight from one side to the other never holds both.
    for (const bool pressing : {false, true})
    {
      for (const DPadDirection& direction : DPAD_DIRECTIONS)
      {
        if (direction.hat_axis != axis)
          continue;

        const bool hat_pressed = direction.positive ? (value > HAT_THRESHOLD) : (value < -HAT_THRESHOLD);
        const bool down = (hat_pressed || GetKey(direction.keycode) != 0.0f);
        if (down == pressing)
          Report(InputSubclass::ControllerButton, direction.keycode, down ? 1.0f : 0.0f, direction.binding, events);
      }
    }
  }
  else
  {
    Report(InputSubclass::ControllerAxis, axis, value, GenericInputBinding::Unknown, events);
  }
}

void PadState::ReleaseAll(std::vector<PadEvent>& events)
{
  for (const auto& [input, reported] : m_reported)
  {
    if (reported.value != 0.0f)
      events.push_back(PadEvent{input.first, input.second, 0.0f, reported.binding});
  }

  m_keys.clear();
  m_axes.clear();
  m_reported.clear();
}

std::vector<u32> PadState::GetCenteredAxes() const
{
  // The core's value for an axis Android gave only at 0 is 0, as Report() passes no first value of 0 on.
  std::vector<u32> axes;
  for (const auto& [axis, value] : m_axes)
  {
    if (!IsHatAxis(axis) && !m_reported.contains({InputSubclass::ControllerAxis, static_cast<u32>(axis)}))
      axes.push_back(static_cast<u32>(axis));
  }

  for (const auto& [input, reported] : m_reported)
  {
    if (input.first == InputSubclass::ControllerAxis && std::abs(reported.value) <= CAPTURE_CENTER)
      axes.push_back(input.second);
  }

  std::sort(axes.begin(), axes.end());
  return axes;
}

std::vector<u32> PadState::GetPushedAxes() const
{
  std::vector<u32> axes;
  for (const auto& [input, reported] : m_reported)
  {
    if (input.first == InputSubclass::ControllerAxis && std::abs(reported.value) > CAPTURE_CENTER)
      axes.push_back(input.second);
  }

  return axes;
}

std::vector<u32> GetCenteredAxes(std::span<const PadState* const> pads)
{
  std::vector<u32> centered;
  std::vector<u32> pushed;
  for (const PadState* pad : pads)
  {
    for (const u32 axis : pad->GetCenteredAxes())
      centered.push_back(axis);
    for (const u32 axis : pad->GetPushedAxes())
      pushed.push_back(axis);
  }

  std::sort(centered.begin(), centered.end());
  centered.erase(std::unique(centered.begin(), centered.end()), centered.end());
  std::erase_if(centered,
                [&pushed](u32 axis) { return std::find(pushed.begin(), pushed.end(), axis) != pushed.end(); });
  return centered;
}

BindingCapture::BindingCapture(u32 device_index, std::vector<u32> centered_axes, bool select_chord)
  : m_device_index(device_index), m_centered_axes(std::move(centered_axes)), m_select_chord(select_chord)
{
}

std::optional<InputBindingKey> BindingCapture::OnEvent(InputBindingKey key, float value)
{
  if (key.source_type != PAD_SOURCE_TYPE || key.source_index != m_device_index)
    return std::nullopt;

  if (key.source_subtype == InputSubclass::ControllerButton)
  {
    if (value < CAPTURE_THRESHOLD)
      return std::nullopt;

    // A chord's button is pressed with Select, and Select with Start opens the menu.
    if (m_select_chord && (key.data == static_cast<u32>(KEYCODE_BUTTON_SELECT) ||
                           key.data == static_cast<u32>(KEYCODE_BUTTON_START)))
    {
      return std::nullopt;
    }

    key.modifier = InputModifier::None;
    return key;
  }

  if (key.source_subtype != InputSubclass::ControllerAxis || m_select_chord)
    return std::nullopt;

  const bool centered =
    (std::find(m_centered_axes.begin(), m_centered_axes.end(), key.data) != m_centered_axes.end());
  if (std::abs(value) <= CAPTURE_CENTER)
  {
    if (!centered)
      m_centered_axes.push_back(key.data);
    return std::nullopt;
  }

  if (!centered || std::abs(value) < CAPTURE_THRESHOLD)
    return std::nullopt;

  key.modifier = (value < 0.0f) ? InputModifier::Negate : InputModifier::None;
  return key;
}

std::optional<u32> ParseDeviceIndex(std::string_view identifier)
{
  if (!identifier.starts_with(DEVICE_PREFIX))
    return std::nullopt;
  return StringUtil::FromChars<u32>(identifier.substr(DEVICE_PREFIX.size()));
}

u32 PadForIndex(std::span<const std::string> ports, u32 index)
{
  if (index >= ports.size())
    return 0;

  const std::optional<u32> number = StringUtil::FromChars<u32>(ports[index]);
  return (number.has_value() && number.value() >= 1 && number.value() <= NUM_PADS) ? (number.value() - 1) : 0;
}

std::vector<std::string> SetPadForIndex(std::span<const std::string> ports, u32 index, u32 pad)
{
  std::vector<std::string> ret(ports.begin(), ports.end());
  if (ret.size() <= index)
    ret.resize(index + 1, "1");
  ret[index] = fmt::format("{}", std::min(pad, NUM_PADS - 1) + 1);
  return ret;
}

u32 RumbleForIndex(std::span<const std::string> rumble, u32 index)
{
  if (index >= rumble.size())
    return 100;

  const std::optional<u32> percent = StringUtil::FromChars<u32>(rumble[index]);
  return (percent.has_value() && percent.value() <= 100) ? percent.value() : 100;
}

std::vector<std::string> SetRumbleForIndex(std::span<const std::string> rumble, u32 index, u32 percent)
{
  std::vector<std::string> ret(rumble.begin(), rumble.end());
  if (ret.size() <= index)
    ret.resize(index + 1, "100");
  ret[index] = fmt::format("{}", std::min(percent, 100u));
  return ret;
}

InputBindingKey MakePortMotorKey(u32 pad, u32 motor)
{
  InputBindingKey key = {};
  key.source_type = PAD_SOURCE_TYPE;
  key.source_index = pad;
  key.source_subtype = InputSubclass::ControllerMotor;
  key.data = motor;
  return key;
}

void RumbleRouter::OnConnected(s32 device_id, u32 index)
{
  if (std::none_of(m_devices.begin(), m_devices.end(), [device_id](const Device& d) { return d.device_id == device_id; }))
    m_devices.push_back(Device{device_id, index, 0});
}

void RumbleRouter::OnDisconnected(s32 device_id)
{
  std::erase_if(m_devices, [device_id](const Device& d) { return d.device_id == device_id; });
  for (Pad& pad : m_pads)
  {
    if (pad.target == device_id)
      pad.target.reset();
  }
}

void RumbleRouter::OnInput(s32 device_id)
{
  for (Device& device : m_devices)
  {
    if (device.device_id == device_id)
      device.last_input = ++m_input_count;
  }
}

void RumbleRouter::SetPads(std::vector<u32> pad_of_index)
{
  m_pad_of_index = std::move(pad_of_index);
}

void RumbleRouter::SetStrengths(std::vector<float> strength_of_index)
{
  m_strength_of_index = std::move(strength_of_index);
}

std::optional<u32> RumbleRouter::GetLastUsedIndex() const
{
  const Device* last = nullptr;
  for (const Device& device : m_devices)
  {
    if (device.last_input > 0 && (!last || device.last_input > last->last_input))
      last = &device;
  }

  return last ? std::optional<u32>(last->index) : std::nullopt;
}

u32 RumbleRouter::PadOf(u32 index) const
{
  return (index < m_pad_of_index.size()) ? m_pad_of_index[index] : 0;
}

float RumbleRouter::StrengthOf(u32 index) const
{
  return (index < m_strength_of_index.size()) ? std::clamp(m_strength_of_index[index], 0.0f, 1.0f) : 1.0f;
}

std::vector<RumbleRouter::Command> RumbleRouter::SetMotor(u32 pad_index, u32 motor, float value)
{
  std::vector<Command> ret;
  if (pad_index >= NUM_PADS || motor > 1)
    return ret;

  Pad& pad = m_pads[pad_index];
  pad.levels[motor] = value;

  // The pad's last-used device, or its first connected one before any is used.
  const Device* target = nullptr;
  for (const Device& device : m_devices)
  {
    if (PadOf(device.index) == pad_index && (!target || device.last_input > target->last_input))
      target = &device;
  }

  if (pad.target.has_value() && (!target || pad.target.value() != target->device_id))
  {
    const s32 old = pad.target.value();
    pad.target.reset();
    if (target && std::any_of(m_devices.begin(), m_devices.end(), [old](const Device& d) { return d.device_id == old; }))
      ret.push_back(Command{old, 0.0f, 0.0f});
  }

  if (!target)
    return ret;

  pad.target = target->device_id;
  const float strength = StrengthOf(target->index);
  ret.push_back(Command{target->device_id, pad.levels[0] * strength, pad.levels[1] * strength});
  return ret;
}

} // namespace AndroidInputKeys
