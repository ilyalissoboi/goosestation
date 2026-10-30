// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/android_input_keys.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <string>
#include <string_view>
#include <vector>

// The numbers below are Android's own KeyEvent.KEYCODE_* and MotionEvent.AXIS_* values, written out so that the
// tests check the mapping against what Android reports, not against the constants it is written with.

using AndroidInputKeys::BindingCapture;
using AndroidInputKeys::GetBindingForAxis;
using AndroidInputKeys::GetBindingForKeyCode;
using AndroidInputKeys::GetDeviceBinding;
using AndroidInputKeys::IndexForDescriptor;
using AndroidInputKeys::PadEvent;
using AndroidInputKeys::PadState;
using AndroidInputKeys::SetDeviceBinding;

TEST(AndroidInputKeys, PadButtons)
{
  EXPECT_EQ(GetBindingForKeyCode(96), GenericInputBinding::Cross);     // KEYCODE_BUTTON_A
  EXPECT_EQ(GetBindingForKeyCode(97), GenericInputBinding::Circle);    // KEYCODE_BUTTON_B
  EXPECT_EQ(GetBindingForKeyCode(99), GenericInputBinding::Square);    // KEYCODE_BUTTON_X
  EXPECT_EQ(GetBindingForKeyCode(100), GenericInputBinding::Triangle); // KEYCODE_BUTTON_Y
  EXPECT_EQ(GetBindingForKeyCode(102), GenericInputBinding::L1);       // KEYCODE_BUTTON_L1
  EXPECT_EQ(GetBindingForKeyCode(103), GenericInputBinding::R1);       // KEYCODE_BUTTON_R1
  EXPECT_EQ(GetBindingForKeyCode(104), GenericInputBinding::L2);       // KEYCODE_BUTTON_L2
  EXPECT_EQ(GetBindingForKeyCode(105), GenericInputBinding::R2);       // KEYCODE_BUTTON_R2
  EXPECT_EQ(GetBindingForKeyCode(106), GenericInputBinding::L3);       // KEYCODE_BUTTON_THUMBL
  EXPECT_EQ(GetBindingForKeyCode(107), GenericInputBinding::R3);       // KEYCODE_BUTTON_THUMBR
  EXPECT_EQ(GetBindingForKeyCode(108), GenericInputBinding::Start);    // KEYCODE_BUTTON_START
  EXPECT_EQ(GetBindingForKeyCode(109), GenericInputBinding::Select);   // KEYCODE_BUTTON_SELECT
  EXPECT_EQ(GetBindingForKeyCode(19), GenericInputBinding::DPadUp);    // KEYCODE_DPAD_UP
  EXPECT_EQ(GetBindingForKeyCode(20), GenericInputBinding::DPadDown);  // KEYCODE_DPAD_DOWN
  EXPECT_EQ(GetBindingForKeyCode(21), GenericInputBinding::DPadLeft);  // KEYCODE_DPAD_LEFT
  EXPECT_EQ(GetBindingForKeyCode(22), GenericInputBinding::DPadRight); // KEYCODE_DPAD_RIGHT
}

// Back and Mode (the home/guide button) open the menu, so they aren't pad buttons.
TEST(AndroidInputKeys, MenuKeysAreNotPadButtons)
{
  EXPECT_FALSE(GetBindingForKeyCode(4).has_value());   // KEYCODE_BACK
  EXPECT_FALSE(GetBindingForKeyCode(110).has_value()); // KEYCODE_BUTTON_MODE
  EXPECT_FALSE(GetBindingForKeyCode(0).has_value());   // KEYCODE_UNKNOWN
  EXPECT_FALSE(GetBindingForKeyCode(-1).has_value());
}

TEST(AndroidInputKeys, Axes)
{
  EXPECT_EQ(GetBindingForAxis(0, false), GenericInputBinding::LeftStickLeft); // AXIS_X
  EXPECT_EQ(GetBindingForAxis(0, true), GenericInputBinding::LeftStickRight);
  EXPECT_EQ(GetBindingForAxis(1, false), GenericInputBinding::LeftStickUp); // AXIS_Y
  EXPECT_EQ(GetBindingForAxis(1, true), GenericInputBinding::LeftStickDown);
  EXPECT_EQ(GetBindingForAxis(11, false), GenericInputBinding::RightStickLeft); // AXIS_Z
  EXPECT_EQ(GetBindingForAxis(11, true), GenericInputBinding::RightStickRight);
  EXPECT_EQ(GetBindingForAxis(14, false), GenericInputBinding::RightStickUp); // AXIS_RZ
  EXPECT_EQ(GetBindingForAxis(14, true), GenericInputBinding::RightStickDown);
  EXPECT_EQ(GetBindingForAxis(17, true), GenericInputBinding::L2); // AXIS_LTRIGGER
  EXPECT_EQ(GetBindingForAxis(18, true), GenericInputBinding::R2); // AXIS_RTRIGGER
  EXPECT_EQ(GetBindingForAxis(23, true), GenericInputBinding::L2); // AXIS_BRAKE
  EXPECT_EQ(GetBindingForAxis(22, true), GenericInputBinding::R2); // AXIS_GAS
  EXPECT_EQ(GetBindingForAxis(15, false), GenericInputBinding::DPadLeft); // AXIS_HAT_X
  EXPECT_EQ(GetBindingForAxis(15, true), GenericInputBinding::DPadRight);
  EXPECT_EQ(GetBindingForAxis(16, false), GenericInputBinding::DPadUp); // AXIS_HAT_Y
  EXPECT_EQ(GetBindingForAxis(16, true), GenericInputBinding::DPadDown);
}

TEST(AndroidInputKeys, TriggersHaveNoNegativeSide)
{
  EXPECT_FALSE(GetBindingForAxis(17, false).has_value());
  EXPECT_FALSE(GetBindingForAxis(18, false).has_value());
  EXPECT_FALSE(GetBindingForAxis(22, false).has_value());
  EXPECT_FALSE(GetBindingForAxis(23, false).has_value());
  EXPECT_FALSE(GetBindingForAxis(2, true).has_value()); // AXIS_PRESSURE
  EXPECT_FALSE(GetBindingForAxis(-1, true).has_value());
}

static bool HasMapping(const GenericInputBindingMapping& mapping, GenericInputBinding binding, std::string_view str)
{
  return std::any_of(mapping.begin(), mapping.end(),
                     [binding, str](const auto& it) { return (it.first == binding && it.second == str); });
}

TEST(AndroidInputKeys, DefaultMapping)
{
  const GenericInputBindingMapping mapping = AndroidInputKeys::GetDefaultMapping(0);
  EXPECT_TRUE(HasMapping(mapping, GenericInputBinding::Cross, "Android-0/Button96"));
  EXPECT_TRUE(HasMapping(mapping, GenericInputBinding::L2, "Android-0/+Axis17"));
  EXPECT_TRUE(HasMapping(mapping, GenericInputBinding::L2, "Android-0/Button104"));
  EXPECT_TRUE(HasMapping(mapping, GenericInputBinding::R2, "Android-0/+Axis18"));
  EXPECT_TRUE(HasMapping(mapping, GenericInputBinding::R2, "Android-0/Button105"));
  EXPECT_TRUE(HasMapping(mapping, GenericInputBinding::DPadLeft, "Android-0/Button21"));
  EXPECT_TRUE(HasMapping(mapping, GenericInputBinding::LeftStickUp, "Android-0/-Axis1"));
  EXPECT_TRUE(HasMapping(mapping, GenericInputBinding::RightStickRight, "Android-0/+Axis11"));
  EXPECT_TRUE(HasMapping(mapping, GenericInputBinding::Start, "Android-0/Button108"));

  // The hat presses the d-pad buttons, so it isn't bound as an axis.
  EXPECT_FALSE(HasMapping(mapping, GenericInputBinding::DPadLeft, "Android-0/-Axis15"));
}

// InputManager::MapController binds the first string it finds for each input, so every pad input needs one.
TEST(AndroidInputKeys, DefaultMappingCoversThePad)
{
  const GenericInputBindingMapping mapping = AndroidInputKeys::GetDefaultMapping(0);
  for (u32 i = static_cast<u32>(GenericInputBinding::DPadUp); i <= static_cast<u32>(GenericInputBinding::R2); i++)
  {
    const GenericInputBinding binding = static_cast<GenericInputBinding>(i);
    if (binding == GenericInputBinding::System)
      continue;

    EXPECT_TRUE(std::any_of(mapping.begin(), mapping.end(), [binding](const auto& it) { return it.first == binding; }))
      << "no mapping for generic binding " << i;
  }
}

TEST(AndroidInputKeys, DefaultMappingUsesTheDeviceIndex)
{
  const GenericInputBindingMapping mapping = AndroidInputKeys::GetDefaultMapping(3);
  EXPECT_TRUE(HasMapping(mapping, GenericInputBinding::Cross, "Android-3/Button96"));
  EXPECT_FALSE(HasMapping(mapping, GenericInputBinding::Cross, "Android-0/Button96"));
}

static std::vector<PadEvent> Key(PadState& pad, s32 keycode, bool pressed)
{
  std::vector<PadEvent> events;
  pad.OnKey(keycode, pressed, events);
  return events;
}

static std::vector<PadEvent> Axis(PadState& pad, s32 axis, float value)
{
  std::vector<PadEvent> events;
  pad.OnAxis(axis, value, events);
  return events;
}

static void ExpectEvent(const PadEvent& event, InputSubclass subclass, u32 number, float value,
                        GenericInputBinding binding)
{
  EXPECT_EQ(event.subclass, subclass);
  EXPECT_EQ(event.number, number);
  EXPECT_FLOAT_EQ(event.value, value);
  EXPECT_EQ(event.binding, binding);
}

static constexpr InputSubclass BUTTON = InputSubclass::ControllerButton;
static constexpr InputSubclass AXIS = InputSubclass::ControllerAxis;

// The trigger's three inputs: KEYCODE_BUTTON_L2, AXIS_LTRIGGER and AXIS_BRAKE.
static void ExpectL2(const std::vector<PadEvent>& events, float value)
{
  ASSERT_EQ(events.size(), 3u);
  for (const PadEvent& event : events)
  {
    EXPECT_FLOAT_EQ(event.value, value);
    EXPECT_EQ(event.binding, GenericInputBinding::L2);
  }
  const auto has = [&events](InputSubclass subclass, u32 number) {
    return std::any_of(events.begin(), events.end(), [subclass, number](const PadEvent& event) {
      return (event.subclass == subclass && event.number == number);
    });
  };
  EXPECT_TRUE(has(BUTTON, 104));
  EXPECT_TRUE(has(AXIS, 17));
  EXPECT_TRUE(has(AXIS, 23));
}

TEST(AndroidInputKeys, ButtonPressAndRelease)
{
  PadState pad;
  std::vector<PadEvent> events = Key(pad, 96, true);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], BUTTON, 96, 1.0f, GenericInputBinding::Cross);

  // A repeated key down is not a second press.
  EXPECT_TRUE(Key(pad, 96, true).empty());

  events = Key(pad, 96, false);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], BUTTON, 96, 0.0f, GenericInputBinding::Cross);
}

// Keys without a pad binding, such as Back, still reach the core, so they can be bound to hotkeys.
TEST(AndroidInputKeys, OtherKeysAreReported)
{
  PadState pad;
  const std::vector<PadEvent> events = Key(pad, 4, true);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], BUTTON, 4, 1.0f, GenericInputBinding::Unknown);
}

TEST(AndroidInputKeys, StickValuesPassAsIs)
{
  PadState pad;
  std::vector<PadEvent> events = Axis(pad, 0, -0.5f);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], AXIS, 0, -0.5f, GenericInputBinding::Unknown);

  EXPECT_TRUE(Axis(pad, 0, -0.5f).empty());

  events = Axis(pad, 14, 1.0f);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], AXIS, 14, 1.0f, GenericInputBinding::Unknown);
}

TEST(AndroidInputKeys, TriggerAsAxis)
{
  PadState pad;
  ExpectL2(Axis(pad, 17, 0.4f), 0.4f);
  ExpectL2(Axis(pad, 17, 1.0f), 1.0f);
  ExpectL2(Axis(pad, 17, 0.0f), 0.0f);
}

TEST(AndroidInputKeys, TriggerAsKey)
{
  PadState pad;
  ExpectL2(Key(pad, 104, true), 1.0f);
  ExpectL2(Key(pad, 104, false), 0.0f);
}

TEST(AndroidInputKeys, TriggerAsBrakeAndGas)
{
  PadState pad;
  ExpectL2(Axis(pad, 23, 0.7f), 0.7f);

  const std::vector<PadEvent> events = Axis(pad, 22, 1.0f);
  ASSERT_EQ(events.size(), 3u);
  for (const PadEvent& event : events)
  {
    EXPECT_FLOAT_EQ(event.value, 1.0f);
    EXPECT_EQ(event.binding, GenericInputBinding::R2);
  }
}

// A pad that reports the trigger as an axis and as a key passes the larger of the two: it presses once, and it
// releases only when both have.
TEST(AndroidInputKeys, TriggerAsAxisAndKey)
{
  PadState pad;
  ExpectL2(Axis(pad, 17, 0.3f), 0.3f);
  ExpectL2(Key(pad, 104, true), 1.0f);
  EXPECT_TRUE(Axis(pad, 17, 1.0f).empty());
  EXPECT_TRUE(Axis(pad, 23, 1.0f).empty());
  EXPECT_TRUE(Axis(pad, 17, 0.6f).empty());
  EXPECT_TRUE(Axis(pad, 23, 0.0f).empty());
  ExpectL2(Key(pad, 104, false), 0.6f);
  ExpectL2(Axis(pad, 17, 0.0f), 0.0f);
}

// Releasing the key first, while the axis is still past it, doesn't drop the trigger to zero.
TEST(AndroidInputKeys, TriggerKeyReleasedBeforeAxis)
{
  PadState pad;
  ExpectL2(Key(pad, 104, true), 1.0f);
  EXPECT_TRUE(Axis(pad, 17, 1.0f).empty());
  EXPECT_TRUE(Key(pad, 104, false).empty());
  ExpectL2(Axis(pad, 17, 0.0f), 0.0f);
}

TEST(AndroidInputKeys, HatPressesDPadButtons)
{
  PadState pad;
  std::vector<PadEvent> events = Axis(pad, 15, -1.0f);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], BUTTON, 21, 1.0f, GenericInputBinding::DPadLeft);

  // Straight from left to right.
  events = Axis(pad, 15, 1.0f);
  ASSERT_EQ(events.size(), 2u);
  ExpectEvent(events[0], BUTTON, 21, 0.0f, GenericInputBinding::DPadLeft);
  ExpectEvent(events[1], BUTTON, 22, 1.0f, GenericInputBinding::DPadRight);

  events = Axis(pad, 15, 0.0f);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], BUTTON, 22, 0.0f, GenericInputBinding::DPadRight);

  events = Axis(pad, 16, -1.0f);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], BUTTON, 19, 1.0f, GenericInputBinding::DPadUp);

  events = Axis(pad, 16, 1.0f);
  ASSERT_EQ(events.size(), 2u);
  ExpectEvent(events[0], BUTTON, 19, 0.0f, GenericInputBinding::DPadUp);
  ExpectEvent(events[1], BUTTON, 20, 1.0f, GenericInputBinding::DPadDown);
}

// A pad that reports the d-pad as a hat and as keys presses each direction once.
TEST(AndroidInputKeys, HatAndDPadKeys)
{
  PadState pad;
  std::vector<PadEvent> events = Axis(pad, 16, -1.0f);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], BUTTON, 19, 1.0f, GenericInputBinding::DPadUp);

  EXPECT_TRUE(Key(pad, 19, true).empty());
  EXPECT_TRUE(Axis(pad, 16, 0.0f).empty());

  events = Key(pad, 19, false);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], BUTTON, 19, 0.0f, GenericInputBinding::DPadUp);
}

TEST(AndroidInputKeys, ReleaseAll)
{
  PadState pad;
  Key(pad, 96, true);
  Axis(pad, 0, -0.7f);
  Axis(pad, 17, 0.5f);
  Axis(pad, 16, 1.0f);
  Axis(pad, 1, 0.0f); // never moved: nothing to release

  std::vector<PadEvent> events;
  pad.ReleaseAll(events);
  ASSERT_EQ(events.size(), 6u);
  for (const PadEvent& event : events)
    EXPECT_FLOAT_EQ(event.value, 0.0f);

  const auto has = [&events](InputSubclass subclass, u32 number) {
    return std::any_of(events.begin(), events.end(), [subclass, number](const PadEvent& event) {
      return (event.subclass == subclass && event.number == number);
    });
  };
  EXPECT_TRUE(has(BUTTON, 96));
  EXPECT_TRUE(has(AXIS, 0));
  EXPECT_TRUE(has(BUTTON, 104));
  EXPECT_TRUE(has(AXIS, 17));
  EXPECT_TRUE(has(AXIS, 23));
  EXPECT_TRUE(has(BUTTON, 20));

  // Everything was forgotten: nothing left to release, and the next press is a press.
  events.clear();
  pad.ReleaseAll(events);
  EXPECT_TRUE(events.empty());
  events = Key(pad, 96, true);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], BUTTON, 96, 1.0f, GenericInputBinding::Cross);
}

// The app releases everything when it loses focus or a game boots or stops, with no key-up or axis event from Android.
// Whatever was held then, the same input afterwards is a new press.
TEST(AndroidInputKeys, PressAgainAfterReleaseAll)
{
  PadState pad;
  Key(pad, 97, true);  // Circle
  Axis(pad, 15, 1.0f); // hat right
  Axis(pad, 0, -1.0f); // left stick, all the way left
  Axis(pad, 18, 1.0f); // R2, as an axis

  std::vector<PadEvent> events;
  pad.ReleaseAll(events);
  EXPECT_FALSE(events.empty());

  events = Key(pad, 97, true);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], BUTTON, 97, 1.0f, GenericInputBinding::Circle);

  events = Axis(pad, 15, 1.0f);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], BUTTON, 22, 1.0f, GenericInputBinding::DPadRight);

  events = Axis(pad, 0, -1.0f);
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], AXIS, 0, -1.0f, GenericInputBinding::Unknown);

  events = Axis(pad, 18, 1.0f);
  ASSERT_EQ(events.size(), 3u);
  ExpectEvent(events[0], AXIS, 18, 1.0f, GenericInputBinding::R2);
}

// Keys without a pad binding, such as BUTTON_C, BUTTON_Z and BUTTON_1 to 16, are buttons too, so they can be bound.
TEST(AndroidInputKeys, UnmappedKeysAreButtons)
{
  PadState pad;
  const std::vector<PadEvent> events = Key(pad, 188, true); // KEYCODE_BUTTON_1
  ASSERT_EQ(events.size(), 1u);
  ExpectEvent(events[0], BUTTON, 188, 1.0f, GenericInputBinding::Unknown);
}

TEST(AndroidInputKeys, IndexForDescriptor)
{
  std::vector<std::string> descriptors = {"a"};

  bool added = false;
  EXPECT_EQ(IndexForDescriptor(descriptors, "b", &added), 1u);
  EXPECT_TRUE(added);
  EXPECT_EQ(descriptors, (std::vector<std::string>{"a", "b"}));

  EXPECT_EQ(IndexForDescriptor(descriptors, "a", &added), 0u);
  EXPECT_FALSE(added);
  EXPECT_EQ(descriptors, (std::vector<std::string>{"a", "b"}));
}

TEST(AndroidInputKeys, SetDeviceBinding)
{
  const std::vector<std::string> bindings = {"Android-0/Button96", "Android-1/Button96"};

  EXPECT_EQ(SetDeviceBinding(bindings, 1, "Button97"),
            (std::vector<std::string>{"Android-0/Button96", "Android-1/Button97"}));
  EXPECT_EQ(SetDeviceBinding(bindings, 1, ""), (std::vector<std::string>{"Android-0/Button96"}));
}

// Every entry of the device goes, the others stay in order, and Android-10 isn't Android-1.
TEST(AndroidInputKeys, SetDeviceBindingTouchesOnlyThatDevice)
{
  const std::vector<std::string> bindings = {"Android-1/Button96", "Android-10/Button99", "SDL-0/A",
                                             "Android-1/+Axis17", "Android-0/Button96"};

  EXPECT_EQ(SetDeviceBinding(bindings, 1, "Button97"),
            (std::vector<std::string>{"Android-10/Button99", "SDL-0/A", "Android-0/Button96", "Android-1/Button97"}));
  EXPECT_EQ(SetDeviceBinding(bindings, 10, ""),
            (std::vector<std::string>{"Android-1/Button96", "SDL-0/A", "Android-1/+Axis17", "Android-0/Button96"}));
  EXPECT_EQ(SetDeviceBinding({}, 2, "+Axis23"), (std::vector<std::string>{"Android-2/+Axis23"}));
}

TEST(AndroidInputKeys, GetDeviceBinding)
{
  const std::vector<std::string> bindings = {"Android-0/Button96", "Android-1/Button96"};

  EXPECT_EQ(GetDeviceBinding(bindings, 1), std::optional<std::string>("Button96"));
  EXPECT_EQ(GetDeviceBinding(bindings, 2), std::nullopt);
}

TEST(AndroidInputKeys, GetDeviceBindingTakesTheFirstOfThatDevice)
{
  const std::vector<std::string> bindings = {"Android-10/Button99", "Android-1/+Axis17", "Android-1/Button104"};

  EXPECT_EQ(GetDeviceBinding(bindings, 1), std::optional<std::string>("+Axis17"));
  EXPECT_EQ(GetDeviceBinding(bindings, 10), std::optional<std::string>("Button99"));
  EXPECT_EQ(GetDeviceBinding(bindings, 0), std::nullopt);
}

// Keys as AndroidInputSource builds them for its events (InputSourceType::Android on Android).
static InputBindingKey PadKey(u32 source_index, InputSubclass subclass, u32 number)
{
  InputBindingKey key = {};
  key.source_type = AndroidInputKeys::PAD_SOURCE_TYPE;
  key.source_index = source_index;
  key.source_subtype = subclass;
  key.data = number;
  return key;
}

static InputBindingKey Captured(u32 source_index, InputSubclass subclass, u32 number, InputModifier modifier)
{
  InputBindingKey key = PadKey(source_index, subclass, number);
  key.modifier = modifier;
  return key;
}

TEST(AndroidInputKeys, CapturesButtonPress)
{
  BindingCapture capture(0);

  EXPECT_EQ(capture.OnEvent(PadKey(0, BUTTON, 96), 1.0f), Captured(0, BUTTON, 96, InputModifier::None));
}

TEST(AndroidInputKeys, IgnoresReleaseAndOtherDevice)
{
  BindingCapture capture(0);

  EXPECT_EQ(capture.OnEvent(PadKey(0, BUTTON, 96), 0.0f), std::nullopt);
  EXPECT_EQ(capture.OnEvent(PadKey(1, BUTTON, 99), 1.0f), std::nullopt);

  InputBindingKey keyboard = PadKey(0, BUTTON, 99);
  keyboard.source_type = InputSourceType::Keyboard;
  EXPECT_EQ(capture.OnEvent(keyboard, 1.0f), std::nullopt);
}

// Review Focus 2: an axis resting away from 0 (a trigger on Z) must come back before it's taken.
TEST(AndroidInputKeys, RestingAxisMustReturnFirst)
{
  BindingCapture capture(0);

  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 11), -0.9f), std::nullopt);
  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 11), -0.6f), std::nullopt);
  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 11), 0.1f), std::nullopt);
  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 11), 0.7f), Captured(0, AXIS, 11, InputModifier::None));
}

TEST(AndroidInputKeys, NegativeDirection)
{
  BindingCapture capture(0);

  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 0), 0.05f), std::nullopt);
  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 0), -0.6f), Captured(0, AXIS, 0, InputModifier::Negate));
}

TEST(AndroidInputKeys, HeldStickIsNotTaken)
{
  BindingCapture capture(0);

  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 1), 0.8f), std::nullopt);
  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 1), 1.0f), std::nullopt);
  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 1), 0.3f), std::nullopt);
  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 1), 0.2f), std::nullopt);
  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 1), 0.8f), Captured(0, AXIS, 1, InputModifier::None));
}

// Each axis comes back on its own: another axis at rest doesn't free a held one.
TEST(AndroidInputKeys, AxesReturnSeparately)
{
  BindingCapture capture(2);

  EXPECT_EQ(capture.OnEvent(PadKey(2, AXIS, 0), 0.0f), std::nullopt);
  EXPECT_EQ(capture.OnEvent(PadKey(2, AXIS, 1), 0.9f), std::nullopt);
  EXPECT_EQ(capture.OnEvent(PadKey(2, AXIS, 0), -1.0f), Captured(2, AXIS, 0, InputModifier::Negate));
}

// Ruling 29: a capture seeded with the axes the pad already has at rest takes a flick on its first event.
TEST(AndroidInputKeys, SeededAxisIsTakenAtOnce)
{
  BindingCapture seeded(0, {0});
  EXPECT_EQ(seeded.OnEvent(PadKey(0, AXIS, 0), 0.8f), Captured(0, AXIS, 0, InputModifier::None));

  BindingCapture unseeded(0);
  EXPECT_EQ(unseeded.OnEvent(PadKey(0, AXIS, 0), 0.8f), std::nullopt);
}

TEST(AndroidInputKeys, SeedIsPerAxis)
{
  BindingCapture capture(0, {11});

  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 14), 0.8f), std::nullopt);
  EXPECT_EQ(capture.OnEvent(PadKey(0, AXIS, 11), -0.9f), Captured(0, AXIS, 11, InputModifier::Negate));
}

// The axes at rest are those the core last got within a quarter of 0. One never reported isn't known to be at rest.
TEST(AndroidInputKeys, CenteredAxes)
{
  PadState pad;
  Axis(pad, 0, 0.1f);
  Axis(pad, 1, 0.8f);
  Axis(pad, 11, 0.0f); // reported by Android, never passed on: still at rest

  EXPECT_EQ(pad.GetCenteredAxes(), (std::vector<u32>{0, 11}));
}

// A trigger takes the merged value the core got: held by its key, its axes aren't at rest.
TEST(AndroidInputKeys, CenteredAxesFollowTheTrigger)
{
  PadState pad;
  Axis(pad, 17, 0.0f);
  Key(pad, 104, true);

  EXPECT_EQ(pad.GetCenteredAxes(), (std::vector<u32>{}));

  Key(pad, 104, false);
  EXPECT_EQ(pad.GetCenteredAxes(), (std::vector<u32>{17, 23}));
}

// The hat presses buttons, so it's never an axis to the core; and ReleaseAll() forgets everything.
TEST(AndroidInputKeys, CenteredAxesSkipTheHatAndReleaseAll)
{
  PadState pad;
  Axis(pad, 15, 0.0f);
  Axis(pad, 0, 0.0f);
  EXPECT_EQ(pad.GetCenteredAxes(), (std::vector<u32>{0}));

  std::vector<PadEvent> events;
  pad.ReleaseAll(events);
  EXPECT_EQ(pad.GetCenteredAxes(), (std::vector<u32>{}));
}

// A pad's axes last reported past a quarter from 0.
TEST(AndroidInputKeys, PushedAxes)
{
  PadState pad;
  Axis(pad, 0, 0.1f);
  Axis(pad, 1, 0.8f);
  Axis(pad, 11, 0.0f);
  Axis(pad, 15, 1.0f); // the hat: buttons to the core

  EXPECT_EQ(pad.GetPushedAxes(), (std::vector<u32>{1}));
}

// Pads sharing a device index: an axis at rest on one counts, unless another has it pushed (a held stick).
TEST(AndroidInputKeys, CenteredAxesOfPadsSharingAnIndex)
{
  PadState a;
  Axis(a, 0, 0.1f);
  Axis(a, 1, 0.0f);
  PadState b;
  Axis(b, 0, 0.8f);
  Axis(b, 11, 0.1f);

  const std::vector<const PadState*> pads = {&a, &b};
  EXPECT_EQ(AndroidInputKeys::GetCenteredAxes(pads), (std::vector<u32>{1, 11}));
  EXPECT_EQ(AndroidInputKeys::GetCenteredAxes(std::vector<const PadState*>{&a}), (std::vector<u32>{0, 1}));
}

// The device names the Android source gives its pads (Android-<n>), parsed in one place for the host and the source.
TEST(AndroidInputKeys, ParseDeviceIndex)
{
  EXPECT_EQ(AndroidInputKeys::ParseDeviceIndex("Android-3"), 3u);
  EXPECT_EQ(AndroidInputKeys::ParseDeviceIndex("Android-0"), 0u);
  EXPECT_EQ(AndroidInputKeys::ParseDeviceIndex("Android-"), std::nullopt);
  EXPECT_EQ(AndroidInputKeys::ParseDeviceIndex("SDL-0"), std::nullopt);
  EXPECT_EQ(AndroidInputKeys::ParseDeviceIndex("Android-x"), std::nullopt);
}
