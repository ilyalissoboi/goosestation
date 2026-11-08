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

/// The pads' device names are this, then their index: Android-<n>.
inline constexpr std::string_view DEVICE_PREFIX = "Android-";

/// Where settings.ini keeps the descriptor of each Android-<n>, in index order: [Android] ControllerDescriptors.
inline constexpr const char* SETTINGS_SECTION = "Android";
inline constexpr const char* DESCRIPTORS_KEY = "ControllerDescriptors";

/// Lists parallel to ControllerDescriptors, entry n for Android-<n>: its pad number (1 to 8, as [PadN]), and its
/// rumble strength in percent (0 to 100). A missing or bad entry means Port 1 (pad 0) and 100%.
inline constexpr const char* PORTS_KEY = "ControllerPorts";
inline constexpr const char* RUMBLE_KEY = "ControllerRumble";

/// The pads (0 to 7) of NUM_CONTROLLER_AND_CARD_PORTS: [Pad1] to [Pad8].
inline constexpr u32 NUM_PADS = 8;

/// Android-<index>'s pad, 0-based, from the ControllerPorts list.
u32 PadForIndex(std::span<const std::string> ports, u32 index);

/// The ControllerPorts list with Android-<index> on pad (0-based); entries before it that are missing become "1".
std::vector<std::string> SetPadForIndex(std::span<const std::string> ports, u32 index, u32 pad);

/// Android-<index>'s rumble strength in percent, from the ControllerRumble list.
u32 RumbleForIndex(std::span<const std::string> rumble, u32 index);

/// The ControllerRumble list with Android-<index> at percent (clamped to 100); entries before it that are missing
/// become "100".
std::vector<std::string> SetRumbleForIndex(std::span<const std::string> rumble, u32 index, u32 percent);

/// The n of an Android-<n> device name, or nullopt for any other name.
std::optional<u32> ParseDeviceIndex(std::string_view identifier);

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

/// The key the core's vibration for pad's motor (0 large or single, 1 small) is bound to on Android: the pad, not a
/// device, as the pad's last-used controller rumbles (RumbleRouter).
InputBindingKey MakePortMotorKey(u32 pad, u32 motor);

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

/// A hotkey's binding for SetDeviceBinding(): Select held with button (Button99), as the core's chord,
/// "Button109 & Android-<index>/<button>". GetDeviceBinding() gives it back the same.
std::string SelectChord(u32 device_index, std::string_view button);

/// [Pad1] Analog and [Hotkeys] ToggleAnalogMode, once MoveAnalogBindings() has moved the first's Android bindings to
/// the second, and whether it moved any.
struct AnalogBindings
{
  std::vector<std::string> analog;
  std::vector<std::string> hotkey;
  bool changed;
};

/// The Controllers page's old Analog Toggle row bound [Pad1] Analog, which the analog mode hotkey replaces: each
/// device's binding there moves to the hotkey, as a Select chord (a chord stays as it is), unless the device has a
/// hotkey already, when it's dropped. Other sources' bindings stay in [Pad1] Analog.
AnalogBindings MoveAnalogBindings(std::span<const std::string> analog, std::span<const std::string> hotkey);

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

  /// Reports every input that isn't at rest at its value, as last reported, changing nothing: for the core to hear
  /// again what's held, after something else let go of the same pad input (the app's touch pad).
  void GetHeld(std::vector<PadEvent>& events) const;

  /// The axes known to be near rest (within BindingCapture's 0.25 of 0), in order: those whose last value reported to
  /// the core was, and those Android has given only at 0, so none was reported. An axis Android has never given isn't
  /// known, and the hat isn't an axis to the core. For seeding a BindingCapture.
  std::vector<u32> GetCenteredAxes() const;

  /// The axes whose last value reported to the core is past BindingCapture's 0.25 from 0, in order.
  std::vector<u32> GetPushedAxes() const;

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

/// The axes near rest on a device index several pads share: those one of them has near rest
/// (PadState::GetCenteredAxes()) and none has pushed (a stick held on another), in order.
std::vector<u32> GetCenteredAxes(std::span<const PadState* const> pads);

/// Picks the input the user means to bind, from one device's events as the InputManager gets them (PadState's, so a
/// trigger's three inputs and the hat's d-pad buttons are already merged):
/// - a button when it's pressed (value >= 0.5); one held as the capture starts only sends its release, so it isn't
///   taken;
/// - an axis when it's pushed half way (|value| >= 0.5), only once it has been seen near 0 (within 0.25), during this
///   capture or before it (centered_axes, from GetCenteredAxes() over every pad with the device index, as
///   AndroidInputSource::GetCenteredAxes() gives them), so one resting away from 0 (a trigger on Z, a stick held as
///   the capture starts) must come back first, and one already at rest is taken on its first push.
/// Other devices' events, and other kinds of input, give nothing.
class BindingCapture
{
public:
  /// With select_chord, for a hotkey: only a button's press is taken, never Select or Start, and axes are passed over.
  explicit BindingCapture(u32 device_index, std::vector<u32> centered_axes = {}, bool select_chord = false);

  /// The key to bind: the event's, with modifier Negate for an axis pushed below 0. nullopt until there is one.
  std::optional<InputBindingKey> OnEvent(InputBindingKey key, float value);

private:
  u32 m_device_index;
  std::vector<u32> m_centered_axes;
  bool m_select_chord;
};

/// Which controller rumbles for each pad, and how hard. Each pad's levels go to its last-used connected controller
/// (or its first connected one, before any is used), times that controller's strength. A controller that stops being
/// the target is told to stop.
class RumbleRouter
{
public:
  struct Command
  {
    s32 device_id;
    float large;
    float small;

    bool operator==(const Command&) const = default;
  };

  void OnConnected(s32 device_id, u32 index);
  void OnDisconnected(s32 device_id);

  /// The device was just used: it's the one its pad's rumble goes to now.
  void OnInput(s32 device_id);

  /// Each index's pad (0-based); a missing one is pad 0.
  void SetPads(std::vector<u32> pad_of_index);

  /// Each index's strength, 0 to 1; a missing one is 1.
  void SetStrengths(std::vector<float> strength_of_index);

  /// Sets pad's motor (0 large or single, 1 small) to value, and returns what to send: the pad's levels to its target,
  /// and a stop to the previous target if it changed and is still connected. Nothing without a device on the pad.
  std::vector<Command> SetMotor(u32 pad, u32 motor, float value);

  /// Sets both of pad's motors at once, as SetMotor() does one: one target, one stop for the previous one.
  std::vector<Command> SetMotors(u32 pad, float large, float small);

  /// The device index of the connected controller used last, if any has been used.
  std::optional<u32> GetLastUsedIndex() const;

private:
  struct Device
  {
    s32 device_id;
    u32 index;
    u64 last_input; // 0 before any
  };

  struct Pad
  {
    float levels[2] = {};
    std::optional<s32> target;
  };

  std::vector<Command> Route(u32 pad);
  u32 PadOf(u32 index) const;
  float StrengthOf(u32 index) const;

  std::vector<Device> m_devices;
  std::vector<u32> m_pad_of_index;
  std::vector<float> m_strength_of_index;
  Pad m_pads[NUM_PADS];
  u64 m_input_count = 0;
};

} // namespace AndroidInputKeys
