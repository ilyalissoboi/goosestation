// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <array>

class Controller;

/// The app's on-screen touch pad, as Port 1's controller takes it: straight to the controller's inputs, by their
/// generic names, with no bindings (Controller::SetBindState(), as Big Picture's own presses do).
namespace TouchPad {

/// What the touch pad holds: its buttons, bit n for the app's TouchButton n (D-pad up, right, down, left, Triangle,
/// Circle, Cross, Square, Select, Start, L1, R1, L2, R2, L3, R3), and the left and right sticks' x and y, each -1 to
/// 1, y down.
struct State
{
  u32 buttons = 0;
  std::array<float, 4> axes{};

  bool operator==(const State&) const = default;
};

/// Sets each of the controller's inputs that differ between from and to to its value in to. An input the
/// controller's type lacks is skipped.
void Apply(Controller& controller, const State& from, const State& to);

/// Sets again every input state holds (pressed buttons, pushed half axes), so a controller the core made anew takes
/// them too. On the same controller nothing changes: SetBindState() ignores an unchanged value.
void Reassert(Controller& controller, const State& state);

/// Port 1's kind for the app: 0 unsupported (no controller, or a type the touch pad can't be), 1 digital (a digital
/// pad, or a DualShock in digital mode), 2 analog (a DualShock in analog mode, or an analog joystick).
s32 KindOf(const Controller* controller);

} // namespace TouchPad
