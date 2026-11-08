// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "touch_pad.h"

#include "core/controller.h"
#include "core/types.h"

#include <algorithm>
#include <cmath>
#include <utility>

namespace TouchPad {

// In the order of State::buttons' bits (the app's TouchButton).
static constexpr std::array BUTTONS = {
  GenericInputBinding::DPadUp,   GenericInputBinding::DPadRight, GenericInputBinding::DPadDown,
  GenericInputBinding::DPadLeft, GenericInputBinding::Triangle,  GenericInputBinding::Circle,
  GenericInputBinding::Cross,    GenericInputBinding::Square,    GenericInputBinding::Select,
  GenericInputBinding::Start,    GenericInputBinding::L1,        GenericInputBinding::R1,
  GenericInputBinding::L2,       GenericInputBinding::R2,        GenericInputBinding::L3,
  GenericInputBinding::R3,
};

// Each of State::axes' negative and positive half axes: left x, left y (down positive), right x, right y.
static constexpr std::array<std::pair<GenericInputBinding, GenericInputBinding>, 4> AXES = {{
  {GenericInputBinding::LeftStickLeft, GenericInputBinding::LeftStickRight},
  {GenericInputBinding::LeftStickUp, GenericInputBinding::LeftStickDown},
  {GenericInputBinding::RightStickLeft, GenericInputBinding::RightStickRight},
  {GenericInputBinding::RightStickUp, GenericInputBinding::RightStickDown},
}};

static void Set(Controller& controller, GenericInputBinding input, float value)
{
  const Controller::ControllerInfo* info = Controller::GetControllerInfo(controller.GetType());
  if (!info)
    return;

  const auto it = std::find_if(info->bindings.begin(), info->bindings.end(),
                               [input](const Controller::ControllerBindingInfo& bi) { return bi.generic_mapping == input; });
  if (it != info->bindings.end())
    controller.SetBindState(it->bind_index, value);
}

static float Negative(float value)
{
  return std::clamp(-value, 0.0f, 1.0f);
}

static float Positive(float value)
{
  return std::clamp(value, 0.0f, 1.0f);
}

void Apply(Controller& controller, const State& from, const State& to)
{
  for (u32 i = 0; i < BUTTONS.size(); i++)
  {
    const bool pressed = (to.buttons & (1u << i)) != 0;
    if (pressed != ((from.buttons & (1u << i)) != 0))
      Set(controller, BUTTONS[i], pressed ? 1.0f : 0.0f);
  }

  for (u32 i = 0; i < AXES.size(); i++)
  {
    if (to.axes[i] == from.axes[i])
      continue;

    // The side it left goes to 0 before the side it's on is set, so the controller never sees both.
    if (Negative(to.axes[i]) == 0.0f)
      Set(controller, AXES[i].first, 0.0f);
    if (Positive(to.axes[i]) == 0.0f)
      Set(controller, AXES[i].second, 0.0f);
    if (Negative(to.axes[i]) > 0.0f)
      Set(controller, AXES[i].first, Negative(to.axes[i]));
    if (Positive(to.axes[i]) > 0.0f)
      Set(controller, AXES[i].second, Positive(to.axes[i]));
  }
}

bool LetsGo(const State& from, const State& to)
{
  if ((from.buttons & ~to.buttons) != 0)
    return true;

  for (u32 i = 0; i < AXES.size(); i++)
  {
    if (Negative(to.axes[i]) < Negative(from.axes[i]) || Positive(to.axes[i]) < Positive(from.axes[i]))
      return true;
  }

  return false;
}

void Reassert(Controller& controller, const State& state)
{
  for (u32 i = 0; i < BUTTONS.size(); i++)
  {
    if ((state.buttons & (1u << i)) != 0)
      Set(controller, BUTTONS[i], 1.0f);
  }

  for (u32 i = 0; i < AXES.size(); i++)
  {
    if (Negative(state.axes[i]) > 0.0f)
      Set(controller, AXES[i].first, Negative(state.axes[i]));
    if (Positive(state.axes[i]) > 0.0f)
      Set(controller, AXES[i].second, Positive(state.axes[i]));
  }
}

s32 KindOf(const Controller* controller)
{
  if (!controller)
    return 0;

  switch (controller->GetType())
  {
    case ControllerType::DigitalController:
      return 1;
    case ControllerType::AnalogController:
      return controller->InAnalogMode() ? 2 : 1;
    case ControllerType::AnalogJoystick:
      return 2;
    default:
      return 0;
  }
}

} // namespace TouchPad
