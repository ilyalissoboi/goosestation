// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "imgui.h"

#include <memory>

/// A deep copy of a frame's ImGui draw data, so the GPU thread can draw it while the emulation thread builds the next
/// frame. Holds no reference to the ImGui context; freed on any thread.
class ImGuiDrawCopy
{
public:
  ~ImGuiDrawCopy();

  static std::unique_ptr<ImGuiDrawCopy> Capture(const ImDrawData& draw_data);

  const ImDrawData* Get() const { return &m_data; }

private:
  ImGuiDrawCopy() = default;

  ImDrawData m_data;
};
