// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "input_manager.h"

#include "common/types.h"

#include <map>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

/// Maps Android's controller keys and axes to the core's inputs. It holds no Android headers, so it builds and is
/// tested on every platform; the Android input source feeds it events and passes its output to the InputManager.
namespace AndroidInputKeys {

// Android's KeyEvent.KEYCODE_* numbers.
inline constexpr s32 KEYCODE_BACK = 4;
inline constexpr s32 KEYCODE_DPAD_UP = 19;
inline constexpr s32 KEYCODE_DPAD_DOWN = 20;
inline constexpr s32 KEYCODE_DPAD_LEFT = 21;
inline constexpr s32 KEYCODE_DPAD_RIGHT = 22;
inline constexpr s32 KEYCODE_BUTTON_A = 96;
inline constexpr s32 KEYCODE_BUTTON_B = 97;
inline constexpr s32 KEYCODE_BUTTON_X = 99;
inline constexpr s32 KEYCODE_BUTTON_Y = 100;
inline constexpr s32 KEYCODE_BUTTON_L1 = 102;
inline constexpr s32 KEYCODE_BUTTON_R1 = 103;
inline constexpr s32 KEYCODE_BUTTON_L2 = 104;
inline constexpr s32 KEYCODE_BUTTON_R2 = 105;
inline constexpr s32 KEYCODE_BUTTON_THUMBL = 106;
inline constexpr s32 KEYCODE_BUTTON_THUMBR = 107;
inline constexpr s32 KEYCODE_BUTTON_START = 108;
inline constexpr s32 KEYCODE_BUTTON_SELECT = 109;
inline constexpr s32 KEYCODE_BUTTON_MODE = 110;

// Android's MotionEvent.AXIS_* numbers.
inline constexpr s32 AXIS_X = 0;
inline constexpr s32 AXIS_Y = 1;
inline constexpr s32 AXIS_Z = 11;
inline constexpr s32 AXIS_RZ = 14;
inline constexpr s32 AXIS_HAT_X = 15;
inline constexpr s32 AXIS_HAT_Y = 16;
inline constexpr s32 AXIS_LTRIGGER = 17;
inline constexpr s32 AXIS_RTRIGGER = 18;
inline constexpr s32 AXIS_GAS = 22;
inline constexpr s32 AXIS_BRAKE = 23;

/// The source type of Android's controllers. Other platforms have no Android source, so they use a type no source has,
/// which lets these helpers build and be tested there.
#ifdef __ANDROID__
inline constexpr InputSourceType PAD_SOURCE_TYPE = InputSourceType::Android;
#else
inline constexpr InputSourceType PAD_SOURCE_TYPE = InputSourceType::Count;
#endif

/// The pad input a key stands for, if any. Back and Mode open the menu, so they have none.
std::optional<GenericInputBinding> GetBindingForKeyCode(s32 keycode);

/// The pad input one side of an axis stands for, if any.
std::optional<GenericInputBinding> GetBindingForAxis(s32 axis, bool positive);

/// The binding strings (Android-<index>/Button96, Android-<index>/+Axis17, ...) for a pad, for
/// InputManager::MapController. That binds the first string for each input; the others are there for the record.
GenericInputBindingMapping GetDefaultMapping(u32 device_index);

/// The device index (the n of Android-<n>) of a controller's descriptor: its position in descriptors. A descriptor not
/// there yet is appended, and *added is set to whether it was.
u32 IndexForDescriptor(std::vector<std::string>& descriptors, std::string_view descriptor, bool* added);

/// The first of a [PadN] key's bindings that is device index's (Android-<index>/...), without that prefix.
std::optional<std::string> GetDeviceBinding(std::span<const std::string> bindings, u32 index);

/// A [PadN] key's bindings with every one of device index's dropped and the others kept in order, then
/// Android-<index>/<binding> appended, unless binding is empty.
std::vector<std::string> SetDeviceBinding(std::span<const std::string> bindings, u32 index, std::string_view binding);

/// An input to report to the core: Button<number> or Axis<number> of the device, at a value.
struct PadEvent
{
  InputSubclass subclass;
  u32 number;
  float value;
  GenericInputBinding binding;
};

/// One device's input state. Turns Android's key and axis events into the events to report:
/// - keys are buttons (pressed 1, released 0), those with no pad input too (GenericInputBinding::Unknown), and axes
///   pass their values as they are;
/// - the hat presses the d-pad buttons (Button19-22) instead of moving an axis, and a d-pad direction that comes
///   both as a key and from the hat is pressed while either holds it;
/// - each trigger's key and two axes (L2, LTRIGGER and BRAKE; R2, RTRIGGER and GAS) all report the largest of the
///   three, so whichever one is bound follows the trigger, presses once and doesn't stick;
/// - only changes are reported.
class PadState
{
public:
  void OnKey(s32 keycode, bool pressed, std::vector<PadEvent>& events);
  void OnAxis(s32 axis, float value, std::vector<PadEvent>& events);

  /// Reports every input that isn't at rest as released, and forgets the state.
  void ReleaseAll(std::vector<PadEvent>& events);

private:
  struct Reported
  {
    float value;
    GenericInputBinding binding;
  };

  float GetKey(s32 keycode) const;
  float GetAxis(s32 axis) const;
  void Report(InputSubclass subclass, s32 number, float value, GenericInputBinding binding,
              std::vector<PadEvent>& events);

  std::map<s32, bool> m_keys;
  std::map<s32, float> m_axes;

  // Last value reported for each input, by (subclass, number). Ordered, so releases come in a fixed order.
  std::map<std::pair<InputSubclass, u32>, Reported> m_reported;
};

/// Picks the input the user means to bind, from one device's events as the InputManager gets them (PadState's, so a
/// trigger's three inputs and the hat's d-pad buttons are already merged):
/// - a button when it's pressed (value >= 0.5); one held as the capture starts only sends its release, so it isn't
///   taken;
/// - an axis when it's pushed half way (|value| >= 0.5), only once it has been seen near 0 (within 0.25) during this
///   capture, so one resting away from 0 (a trigger on Z, a stick held as the capture starts) must come back first.
/// Other devices' events, and other kinds of input, give nothing.
class BindingCapture
{
public:
  explicit BindingCapture(u32 device_index);

  /// The key to bind: the event's, with modifier Negate for an axis pushed below 0. nullopt until there is one.
  std::optional<InputBindingKey> OnEvent(InputBindingKey key, float value);

private:
  u32 m_device_index;
  std::vector<u32> m_centered_axes;
};

} // namespace AndroidInputKeys
