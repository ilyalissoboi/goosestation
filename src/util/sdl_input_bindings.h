// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#pragma once

#include "input_manager.h"

#include "common/small_string.h"

#include <array>
#include <optional>
#include <string_view>

/// Names of SDL controller inputs in binding strings (SDL-0/A, SDL-0/+LeftX, ...). They are saved in settings, so
/// they must keep naming the same inputs.
namespace SDLInputBindings {

inline constexpr std::array<const char*, 6> AXIS_NAMES = {
  "LeftX",        // SDL_GAMEPAD_AXIS_LEFTX
  "LeftY",        // SDL_GAMEPAD_AXIS_LEFTY
  "RightX",       // SDL_GAMEPAD_AXIS_RIGHTX
  "RightY",       // SDL_GAMEPAD_AXIS_RIGHTY
  "LeftTrigger",  // SDL_GAMEPAD_AXIS_LEFT_TRIGGER
  "RightTrigger", // SDL_GAMEPAD_AXIS_RIGHT_TRIGGER
};

inline constexpr std::array<const char*, 21> BUTTON_NAMES = {
  "A",             // SDL_GAMEPAD_BUTTON_SOUTH
  "B",             // SDL_GAMEPAD_BUTTON_EAST
  "X",             // SDL_GAMEPAD_BUTTON_WEST
  "Y",             // SDL_GAMEPAD_BUTTON_NORTH
  "Back",          // SDL_GAMEPAD_BUTTON_BACK
  "Guide",         // SDL_GAMEPAD_BUTTON_GUIDE
  "Start",         // SDL_GAMEPAD_BUTTON_START
  "LeftStick",     // SDL_GAMEPAD_BUTTON_LEFT_STICK
  "RightStick",    // SDL_GAMEPAD_BUTTON_RIGHT_STICK
  "LeftShoulder",  // SDL_GAMEPAD_BUTTON_LEFT_SHOULDER
  "RightShoulder", // SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER
  "DPadUp",        // SDL_GAMEPAD_BUTTON_DPAD_UP
  "DPadDown",      // SDL_GAMEPAD_BUTTON_DPAD_DOWN
  "DPadLeft",      // SDL_GAMEPAD_BUTTON_DPAD_LEFT
  "DPadRight",     // SDL_GAMEPAD_BUTTON_DPAD_RIGHT
  "Misc1",         // SDL_GAMEPAD_BUTTON_MISC1
  "Paddle1",       // SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1 (Xbox Elite P1)
  "Paddle2",       // SDL_GAMEPAD_BUTTON_LEFT_PADDLE1 (Xbox Elite P3)
  "Paddle3",       // SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2 (Xbox Elite P2)
  "Paddle4",       // SDL_GAMEPAD_BUTTON_LEFT_PADDLE2 (Xbox Elite P4)
  "Touchpad",      // SDL_GAMEPAD_BUTTON_TOUCHPAD
};

inline constexpr std::array<const char*, 4> HAT_DIRECTION_NAMES = {
  "North",
  "East",
  "South",
  "West",
};

std::optional<InputBindingKey> ParseKey(std::string_view device, std::string_view binding);
TinyString KeyToString(InputBindingKey key);

/// A face button's label, as SDL reports it.
enum class FaceLabel : u8
{
  A,
  B,
  X,
  Y,
  Other,
};

/// SDL2 numbered the four face buttons by their A/B/X/Y labels, SDL3 by position (south, east, west, north). From the
/// labels at those positions, returns the SDL2 number of each, so that bindings made with SDL2 keep the same buttons
/// on controllers with Nintendo layouts. Controllers without A/B/X/Y labels keep the positions.
std::array<u8, 4> GetFaceButtonIndices(const std::array<FaceLabel, 4>& labels);

} // namespace SDLInputBindings
