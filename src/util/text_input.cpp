// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "text_input.h"

#include "common/string_util.h"

#include "fmt/format.h"

#include <cstring>

namespace TextInput {

// Steam's EFloatingGamepadTextInputMode values.
static constexpr int STEAM_KEYBOARD_MODE_SINGLE_LINE = 0;
static constexpr int STEAM_KEYBOARD_MODE_NUMERIC = 3;

/// An environment variable read the way SDL reads its hints: unset or empty is `default_value`, "0" and
/// "false" are false, anything else is true.
static bool GetEnvBoolean(const char* value, bool default_value)
{
  if (!value || *value == '\0')
    return default_value;

  return (std::strcmp(value, "0") != 0 && StringUtil::Strcasecmp(value, "false") != 0);
}

} // namespace TextInput

bool TextInput::IsKeyboardActivation(InputDevice activated_with, InputDevice last_button_device)
{
  return (activated_with != InputDevice::Mouse && last_button_device == InputDevice::Keyboard);
}

std::string_view TextInput::GetFirstLine(std::string_view text)
{
  const std::string_view::size_type start = text.find_first_not_of("\r\n");
  if (start == std::string_view::npos)
    return {};

  text = text.substr(start);
  return text.substr(0, text.find_first_of("\r\n"));
}

bool TextInput::IsSteamKeyboardEnabled(const char* sdl_enable_env, const char* steam_deck_env)
{
  return GetEnvBoolean(sdl_enable_env, GetEnvBoolean(steam_deck_env, false));
}

std::string TextInput::GetSteamKeyboardShowURL(const Request& request)
{
  return fmt::format("steam://open/keyboard?XPosition={}&YPosition={}&Width={}&Height={}&Mode={}", request.x, request.y,
                     request.width, request.height,
                     request.numeric ? STEAM_KEYBOARD_MODE_NUMERIC : STEAM_KEYBOARD_MODE_SINGLE_LINE);
}
