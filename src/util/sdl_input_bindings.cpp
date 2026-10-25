// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#include "sdl_input_bindings.h"

#include "common/string_util.h"

std::optional<InputBindingKey> SDLInputBindings::ParseKey(std::string_view device, std::string_view binding)
{
  if (!device.starts_with("SDL-") || binding.empty())
    return std::nullopt;

  const std::optional<s32> player_id = StringUtil::FromChars<s32>(device.substr(4));
  if (!player_id.has_value() || player_id.value() < 0)
    return std::nullopt;

  InputBindingKey key = {};
  key.source_type = InputSourceType::SDL;
  key.source_index = static_cast<u32>(player_id.value());

  if (binding.ends_with("Motor"))
  {
    key.source_subtype = InputSubclass::ControllerMotor;
    if (binding == "LargeMotor")
    {
      key.data = 0;
      return key;
    }
    else if (binding == "SmallMotor")
    {
      key.data = 1;
      return key;
    }
    else
    {
      return std::nullopt;
    }
  }
  else if (binding.ends_with("Haptic"))
  {
    key.source_subtype = InputSubclass::ControllerHaptic;
    key.data = 0;
    return key;
  }
  else if (binding[0] == '+' || binding[0] == '-')
  {
    // likely an axis
    const std::string_view axis_name(binding.substr(1));

    if (axis_name.starts_with("Axis"))
    {
      std::string_view end;
      if (auto value = StringUtil::FromChars<u32>(axis_name.substr(4), 10, &end))
      {
        key.source_subtype = InputSubclass::ControllerAxis;
        key.data = *value + static_cast<u32>(std::size(AXIS_NAMES));
        key.modifier = (binding[0] == '-') ? InputModifier::Negate : InputModifier::None;
        key.invert = (end == "~");
        return key;
      }
    }
    for (u32 i = 0; i < std::size(AXIS_NAMES); i++)
    {
      if (axis_name == AXIS_NAMES[i])
      {
        // found an axis!
        key.source_subtype = InputSubclass::ControllerAxis;
        key.data = i;
        key.modifier = (binding[0] == '-') ? InputModifier::Negate : InputModifier::None;
        return key;
      }
    }
  }
  else if (binding.starts_with("FullAxis"))
  {
    std::string_view end;
    if (auto value = StringUtil::FromChars<u32>(binding.substr(8), 10, &end))
    {
      key.source_subtype = InputSubclass::ControllerAxis;
      key.data = *value + static_cast<u32>(std::size(AXIS_NAMES));
      key.modifier = InputModifier::FullAxis;
      key.invert = (end == "~");
      return key;
    }
  }
  else if (binding.starts_with("Hat"))
  {
    std::string_view hat_dir;
    if (auto value = StringUtil::FromChars<u32>(binding.substr(3), 10, &hat_dir); value.has_value() && !hat_dir.empty())
    {
      for (u8 dir = 0; dir < static_cast<u8>(std::size(HAT_DIRECTION_NAMES)); dir++)
      {
        if (hat_dir == HAT_DIRECTION_NAMES[dir])
        {
          key.source_subtype = InputSubclass::ControllerHat;
          key.data = value.value() * static_cast<u32>(std::size(HAT_DIRECTION_NAMES)) + dir;
          return key;
        }
      }
    }
  }
  else
  {
    // must be a button
    if (binding.starts_with("Button"))
    {
      if (auto value = StringUtil::FromChars<u32>(binding.substr(6)))
      {
        key.source_subtype = InputSubclass::ControllerButton;
        key.data = *value + static_cast<u32>(std::size(BUTTON_NAMES));
        return key;
      }
    }
    for (u32 i = 0; i < std::size(BUTTON_NAMES); i++)
    {
      if (binding == BUTTON_NAMES[i])
      {
        key.source_subtype = InputSubclass::ControllerButton;
        key.data = i;
        return key;
      }
    }
  }

  // unknown axis/button
  return std::nullopt;
}

TinyString SDLInputBindings::KeyToString(InputBindingKey key)
{
  TinyString ret;

  if (key.source_type == InputSourceType::SDL)
  {
    if (key.source_subtype == InputSubclass::ControllerAxis)
    {
      const char* modifier =
        (key.modifier == InputModifier::FullAxis ? "Full" : (key.modifier == InputModifier::Negate ? "-" : "+"));
      if (key.data < std::size(AXIS_NAMES))
      {
        ret.format("SDL-{}/{}{}", static_cast<u32>(key.source_index), modifier, AXIS_NAMES[key.data]);
      }
      else
      {
        ret.format("SDL-{}/{}Axis{}{}", static_cast<u32>(key.source_index), modifier,
                   key.data - static_cast<u32>(std::size(AXIS_NAMES)), key.invert ? "~" : "");
      }
    }
    else if (key.source_subtype == InputSubclass::ControllerButton)
    {
      if (key.data < std::size(BUTTON_NAMES))
      {
        ret.format("SDL-{}/{}", static_cast<u32>(key.source_index), BUTTON_NAMES[key.data]);
      }
      else
      {
        ret.format("SDL-{}/Button{}", static_cast<u32>(key.source_index),
                   key.data - static_cast<u32>(std::size(BUTTON_NAMES)));
      }
    }
    else if (key.source_subtype == InputSubclass::ControllerHat)
    {
      const u32 hat_index = key.data / static_cast<u32>(std::size(HAT_DIRECTION_NAMES));
      const u32 hat_direction = key.data % static_cast<u32>(std::size(HAT_DIRECTION_NAMES));
      ret.format("SDL-{}/Hat{}{}", static_cast<u32>(key.source_index), hat_index, HAT_DIRECTION_NAMES[hat_direction]);
    }
    else if (key.source_subtype == InputSubclass::ControllerMotor)
    {
      ret.format("SDL-{}/{}Motor", static_cast<u32>(key.source_index), key.data ? "Large" : "Small");
    }
    else if (key.source_subtype == InputSubclass::ControllerHaptic)
    {
      ret.format("SDL-{}/Haptic", static_cast<u32>(key.source_index));
    }
  }

  return ret;
}
