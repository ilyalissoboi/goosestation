// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <string>
#include <string_view>

// Text entry in Big Picture, with the platform's own keyboard: Steam's on a Steam Deck in game mode, the
// system's on Android, a physical one on desktop. ImGuiManager tells the host when a text field starts and
// stops being edited (Host::OnTextInputStarted()/OnTextInputEnded()), and the host shows its keyboard.

namespace TextInput {

/// A text field that started being edited.
struct Request
{
  /// The field, in window pixels.
  s32 x;
  s32 y;
  s32 width;
  s32 height;

  /// Only numbers can be typed.
  bool numeric;

  /// Activated with a physical keyboard, so no on-screen keyboard is needed.
  bool keyboard_used;
};

/// A kind of input device.
enum class InputDevice : u8
{
  Mouse,
  Keyboard,
  Gamepad,
};

/// Whether a field was activated with a physical keyboard, from what ImGui says activated it and what pressed the
/// last key or button. ImGui's answer alone isn't enough: keys it doesn't count as navigation (keypad Enter) leave it
/// saying gamepad.
bool IsKeyboardActivation(InputDevice activated_with, InputDevice last_button_device);

/// The first non-empty line of pasted text, as it is: spaces are kept, since some values (passphrases)
/// can start or end with them.
std::string_view GetFirstLine(std::string_view text);

/// Whether Steam asks for its on-screen keyboard, from the values of the SDL_ENABLE_STEAM_SCREEN_KEYBOARD and
/// SteamDeck environment variables (null when unset). Steam sets the first for games in Big Picture mode, older
/// versions only the second on a Steam Deck.
bool IsSteamKeyboardEnabled(const char* sdl_enable_env, const char* steam_deck_env);

/// The link that shows Steam's on-screen keyboard next to the field.
std::string GetSteamKeyboardShowURL(const Request& request);

/// The link that hides Steam's on-screen keyboard.
inline constexpr const char* STEAM_KEYBOARD_CLOSE_URL = "steam://close/keyboard";

} // namespace TextInput
