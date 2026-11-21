// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "imgui_draw_copy.h"

ImGuiDrawCopy::~ImGuiDrawCopy()
{
  for (ImDrawList* list : m_data.CmdLists)
    IM_DELETE(list);
}

std::unique_ptr<ImGuiDrawCopy> ImGuiDrawCopy::Capture(const ImDrawData& draw_data)
{
  std::unique_ptr<ImGuiDrawCopy> copy(new ImGuiDrawCopy());
  ImDrawData& data = copy->m_data;
  data.Valid = draw_data.Valid;
  data.CmdListsCount = draw_data.CmdListsCount;
  data.TotalIdxCount = draw_data.TotalIdxCount;
  data.TotalVtxCount = draw_data.TotalVtxCount;
  data.DisplayPos = draw_data.DisplayPos;
  data.DisplaySize = draw_data.DisplaySize;
  data.FramebufferScale = draw_data.FramebufferScale;
  data.OwnerViewport = nullptr;

  data.CmdLists.reserve(draw_data.CmdLists.Size);
  for (const ImDrawList* list : draw_data.CmdLists)
  {
    // The clone's _Data points at the context's shared data, which drawing never reads: only adding to the list does.
    ImDrawList* clone = list->CloneOutput();
    clone->_Data = nullptr;
    data.CmdLists.push_back(clone);
  }

  return copy;
}
