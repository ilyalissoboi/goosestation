// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Android's stand-in for the fullscreen UI (android/app/src/cpp/fullscreen_ui_stubs.cpp), with the real
// ImGuiFullscreen. No GPU device is needed: core_host_stubs.cpp finds no resources, so a texture fails to load and is
// cached as the (empty) placeholder.

#include "core/fullscreen_ui.h"

#include "util/imgui_fullscreen.h"

#include <gtest/gtest.h>

// The loading screen's logo is cached against the GPU device it was made on, so FullscreenUI::Shutdown() has to drop
// it. A logo left over from an OpenGL ES device was drawn as a Vulkan texture by the next Vulkan device, and crashed.
TEST(FullscreenUIStubs, ShutdownReleasesCachedTextures)
{
  // InvalidateCachedTexture() says whether the cache held the texture (and drops it), so check it caches at all.
  ImGuiFullscreen::GetCachedTexture("images/placeholder.png");
  ASSERT_TRUE(ImGuiFullscreen::InvalidateCachedTexture("images/placeholder.png"));

  ImGuiFullscreen::GetCachedTexture("images/goosestation.png");

  FullscreenUI::Shutdown();

  EXPECT_FALSE(ImGuiFullscreen::InvalidateCachedTexture("images/goosestation.png"));
}
