// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/imgui_draw_copy.h"

#include "imgui.h"

#include <gtest/gtest.h>

#include <cstring>

namespace {

// An ImGui context with a built font atlas, nothing else.
class ImGuiDrawCopyTest : public ::testing::Test
{
protected:
  void SetUp() override
  {
    m_context = ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.DisplaySize = ImVec2(640, 480);
    unsigned char* pixels;
    int width, height;
    io.Fonts->GetTexDataAsRGBA32(&pixels, &width, &height);
    io.Fonts->SetTexID(reinterpret_cast<ImTextureID>(static_cast<uintptr_t>(0x100)));
  }

  void TearDown() override { ImGui::DestroyContext(m_context); }

  static void DrawTwoWindows(const char* text)
  {
    ImGui::NewFrame();
    ImGui::SetNextWindowPos(ImVec2(10, 10));
    ImGui::SetNextWindowSize(ImVec2(250, 100)); // a sized window shows on its first frame
    ImGui::Begin("first");
    ImGui::TextUnformatted(text);
    ImGui::End();
    ImGui::SetNextWindowPos(ImVec2(300, 200));
    ImGui::SetNextWindowSize(ImVec2(100, 100));
    ImGui::Begin("second");
    ImGui::Image(reinterpret_cast<ImTextureID>(static_cast<uintptr_t>(0x200)), ImVec2(32, 32));
    ImGui::End();
    ImGui::Render();
  }

  static void ExpectEqual(const ImDrawData& a, const ImDrawData& b)
  {
    ASSERT_EQ(a.CmdListsCount, b.CmdListsCount);
    EXPECT_EQ(a.TotalVtxCount, b.TotalVtxCount);
    EXPECT_EQ(a.TotalIdxCount, b.TotalIdxCount);
    EXPECT_EQ(a.DisplaySize.x, b.DisplaySize.x);
    EXPECT_EQ(a.DisplaySize.y, b.DisplaySize.y);
    for (int i = 0; i < a.CmdListsCount; i++)
    {
      const ImDrawList* la = a.CmdLists[i];
      const ImDrawList* lb = b.CmdLists[i];
      ASSERT_EQ(la->VtxBuffer.Size, lb->VtxBuffer.Size);
      ASSERT_EQ(la->IdxBuffer.Size, lb->IdxBuffer.Size);
      ASSERT_EQ(la->CmdBuffer.Size, lb->CmdBuffer.Size);
      EXPECT_EQ(std::memcmp(la->VtxBuffer.Data, lb->VtxBuffer.Data, sizeof(ImDrawVert) * la->VtxBuffer.Size), 0);
      EXPECT_EQ(std::memcmp(la->IdxBuffer.Data, lb->IdxBuffer.Data, sizeof(ImDrawIdx) * la->IdxBuffer.Size), 0);
      for (int c = 0; c < la->CmdBuffer.Size; c++)
      {
        const ImDrawCmd& ca = la->CmdBuffer[c];
        const ImDrawCmd& cb = lb->CmdBuffer[c];
        EXPECT_EQ(ca.TextureId, cb.TextureId);
        EXPECT_EQ(ca.ElemCount, cb.ElemCount);
        EXPECT_EQ(ca.IdxOffset, cb.IdxOffset);
        EXPECT_EQ(ca.VtxOffset, cb.VtxOffset);
        EXPECT_EQ(std::memcmp(&ca.ClipRect, &cb.ClipRect, sizeof(ImVec4)), 0);
      }
    }
  }

  ImGuiContext* m_context = nullptr;
};

} // namespace

TEST_F(ImGuiDrawCopyTest, ACopyHasEveryListsVerticesIndicesAndCommands)
{
  DrawTwoWindows("hello");
  const ImDrawData* source = ImGui::GetDrawData();
  ASSERT_GE(source->CmdListsCount, 2);

  const std::unique_ptr<ImGuiDrawCopy> copy = ImGuiDrawCopy::Capture(*source);
  ExpectEqual(*source, *copy->Get());

  bool image_seen = false;
  for (const ImDrawList* list : copy->Get()->CmdLists)
  {
    for (const ImDrawCmd& cmd : list->CmdBuffer)
      image_seen |= (cmd.TextureId == reinterpret_cast<ImTextureID>(static_cast<uintptr_t>(0x200)));
  }
  EXPECT_TRUE(image_seen);
}

TEST_F(ImGuiDrawCopyTest, ACopyOutlivesTheNextFrame)
{
  DrawTwoWindows("first frame");
  const std::unique_ptr<ImGuiDrawCopy> copy = ImGuiDrawCopy::Capture(*ImGui::GetDrawData());
  const int vertices = copy->Get()->TotalVtxCount;
  const ImDrawVert first = copy->Get()->CmdLists[0]->VtxBuffer[0];

  DrawTwoWindows("a much longer second frame of text, so the buffers change");
  EXPECT_NE(ImGui::GetDrawData()->TotalVtxCount, vertices);
  EXPECT_EQ(copy->Get()->TotalVtxCount, vertices);
  EXPECT_EQ(std::memcmp(&copy->Get()->CmdLists[0]->VtxBuffer[0], &first, sizeof(first)), 0);
}

TEST_F(ImGuiDrawCopyTest, AnEmptyFrameCopiesAsEmpty)
{
  ImGui::NewFrame();
  ImGui::Render();
  const std::unique_ptr<ImGuiDrawCopy> copy = ImGuiDrawCopy::Capture(*ImGui::GetDrawData());
  EXPECT_EQ(copy->Get()->CmdListsCount, 0);
  EXPECT_EQ(copy->Get()->TotalVtxCount, 0);
}
