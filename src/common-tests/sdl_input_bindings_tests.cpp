// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/sdl_input_bindings.h"

#include "fmt/format.h"

#include <gtest/gtest.h>

static InputBindingKey Parse(std::string_view binding)
{
  const std::optional<InputBindingKey> key = SDLInputBindings::ParseKey("SDL-3", binding);
  EXPECT_TRUE(key.has_value()) << binding;
  return key.value_or(InputBindingKey{});
}

static void ExpectKey(std::string_view binding, InputSubclass subtype, u32 data,
                      InputModifier modifier = InputModifier::None, bool invert = false)
{
  const InputBindingKey key = Parse(binding);
  EXPECT_EQ(key.source_type, InputSourceType::SDL) << binding;
  EXPECT_EQ(key.source_index, 3u) << binding;
  EXPECT_EQ(key.source_subtype, subtype) << binding;
  EXPECT_EQ(key.data, data) << binding;
  EXPECT_EQ(key.modifier, modifier) << binding;
  EXPECT_EQ(key.invert, invert ? 1u : 0u) << binding;
}

// Bindings saved by earlier versions must keep naming the same inputs.
TEST(SDLInputBindings, ParseButtons)
{
  ExpectKey("A", InputSubclass::ControllerButton, 0);
  ExpectKey("Y", InputSubclass::ControllerButton, 3);
  ExpectKey("DPadRight", InputSubclass::ControllerButton, 14);
  ExpectKey("Paddle1", InputSubclass::ControllerButton, 16);
  ExpectKey("Touchpad", InputSubclass::ControllerButton, 20);

  // Joystick buttons come after the named ones.
  ExpectKey("Button0", InputSubclass::ControllerButton, 21);
  ExpectKey("Button5", InputSubclass::ControllerButton, 26);
}

TEST(SDLInputBindings, ParseAxes)
{
  ExpectKey("+LeftX", InputSubclass::ControllerAxis, 0);
  ExpectKey("-LeftY", InputSubclass::ControllerAxis, 1, InputModifier::Negate);
  ExpectKey("+RightTrigger", InputSubclass::ControllerAxis, 5);

  // Joystick axes come after the named ones.
  ExpectKey("+Axis2", InputSubclass::ControllerAxis, 8);
  ExpectKey("-Axis2~", InputSubclass::ControllerAxis, 8, InputModifier::Negate, true);
  ExpectKey("FullAxis1", InputSubclass::ControllerAxis, 7, InputModifier::FullAxis);
  ExpectKey("FullAxis1~", InputSubclass::ControllerAxis, 7, InputModifier::FullAxis, true);
}

TEST(SDLInputBindings, ParseHatsAndMotors)
{
  ExpectKey("Hat0North", InputSubclass::ControllerHat, 0);
  ExpectKey("Hat0West", InputSubclass::ControllerHat, 3);
  ExpectKey("Hat1South", InputSubclass::ControllerHat, 6);

  ExpectKey("LargeMotor", InputSubclass::ControllerMotor, 0);
  ExpectKey("SmallMotor", InputSubclass::ControllerMotor, 1);
  ExpectKey("Haptic", InputSubclass::ControllerHaptic, 0);
}

TEST(SDLInputBindings, ParseRejects)
{
  EXPECT_FALSE(SDLInputBindings::ParseKey("Keyboard", "A").has_value());
  EXPECT_FALSE(SDLInputBindings::ParseKey("SDL-", "A").has_value());
  EXPECT_FALSE(SDLInputBindings::ParseKey("SDL--1", "A").has_value());
  EXPECT_FALSE(SDLInputBindings::ParseKey("SDL-0", "").has_value());
  EXPECT_FALSE(SDLInputBindings::ParseKey("SDL-0", "Bogus").has_value());
  EXPECT_FALSE(SDLInputBindings::ParseKey("SDL-0", "+Bogus").has_value());
  EXPECT_FALSE(SDLInputBindings::ParseKey("SDL-0", "Hat0Up").has_value());
  EXPECT_FALSE(SDLInputBindings::ParseKey("SDL-0", "MediumMotor").has_value());
}

TEST(SDLInputBindings, RoundTrip)
{
  for (const char* binding : {"A", "Start", "Touchpad", "Button0", "Button5", "+LeftX", "-LeftY", "+RightTrigger",
                              "+Axis2", "-Axis2~", "FullAxis1", "FullAxis1~", "Hat0North", "Hat1South", "Haptic"})
  {
    EXPECT_EQ(std::string_view(SDLInputBindings::KeyToString(Parse(binding))), fmt::format("SDL-3/{}", binding));
  }
}

TEST(SDLInputBindings, MotorNames)
{
  // Motor 0 is the large one when parsed, but is written as SmallMotor. That is how it has always been; keep it.
  EXPECT_EQ(std::string_view(SDLInputBindings::KeyToString(Parse("LargeMotor"))), "SDL-3/SmallMotor");
  EXPECT_EQ(std::string_view(SDLInputBindings::KeyToString(Parse("SmallMotor"))), "SDL-3/LargeMotor");
}
