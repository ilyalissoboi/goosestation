// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/gpu_presenter.h"
#include "core/settings.h"

#include <gtest/gtest.h>

// The rects GPU::CalculateDrawRect gave before the presenter took it over (recorded on 2026-11-21 at e33d80045).

namespace {

GPUDisplayParams Display(u16 width, u16 height, u16 origin_left, u16 origin_top, u16 vram_width, u16 vram_height,
                         float aspect_ratio)
{
  GPUDisplayParams params = {};
  params.display_width = width;
  params.display_height = height;
  params.display_origin_left = origin_left;
  params.display_origin_top = origin_top;
  params.display_vram_width = vram_width;
  params.display_vram_height = vram_height;
  params.display_aspect_ratio = aspect_ratio;
  return params;
}

void ExpectRects(const GPUDisplayParams& params, const Settings& settings, s32 window_width, s32 window_height,
                 bool apply_aspect_ratio, GSVector4i display, GSVector4i draw)
{
  GSVector4i display_rect, draw_rect;
  GPUPresenter::CalculateDrawRect(params, settings, window_width, window_height, true, apply_aspect_ratio,
                                  &display_rect, &draw_rect);
  EXPECT_TRUE(display_rect.eq(display)) << display_rect.x << "," << display_rect.y << "," << display_rect.z << ","
                                        << display_rect.w;
  EXPECT_TRUE(draw_rect.eq(draw)) << draw_rect.x << "," << draw_rect.y << "," << draw_rect.z << "," << draw_rect.w;
}

const GPUDisplayParams NTSC_320 = Display(320, 240, 0, 0, 320, 240, 4.0f / 3.0f);

} // namespace

TEST(GPUPresenterDrawRect, NTSC320x240InA1920x1080WindowIsPillarboxed)
{
  ExpectRects(NTSC_320, Settings(), 1920, 1080, true, GSVector4i(240, 0, 1680, 1080), GSVector4i(240, 0, 1680, 1080));
}

TEST(GPUPresenterDrawRect, PAL368x288At16By9FillsTheWindow)
{
  Settings settings;
  settings.display_aspect_ratio = DisplayAspectRatio::R16_9;
  ExpectRects(Display(368, 288, 0, 0, 368, 288, 16.0f / 9.0f), settings, 1920, 1080, true,
              GSVector4i(0, 0, 1920, 1080), GSVector4i(0, 0, 1920, 1080));
}

TEST(GPUPresenterDrawRect, IntegerScalingRoundsDown)
{
  Settings settings;
  settings.display_scaling = DisplayScalingMode::NearestInteger;
  ExpectRects(NTSC_320, settings, 1280, 720, true, GSVector4i(160, 0, 1120, 720), GSVector4i(160, 0, 1120, 720));
}

TEST(GPUPresenterDrawRect, Rotate90SwapsTheWindow)
{
  Settings settings;
  settings.display_rotation = DisplayRotation::Rotate90;
  ExpectRects(NTSC_320, settings, 1920, 1080, true, GSVector4i(555, 0, 1365, 1080), GSVector4i(555, 0, 1365, 1080));
}

TEST(GPUPresenterDrawRect, ShowVRAMUsesTheWholeVRAM)
{
  Settings settings;
  settings.debugging.show_vram = true;
  ExpectRects(Display(320, 240, 0, 0, 320, 240, 2.0f), settings, 1920, 1080, true, GSVector4i(0, 60, 1920, 1020),
              GSVector4i(0, 60, 1920, 1020));
}

TEST(GPUPresenterDrawRect, AnOriginKeepsThePictureInsideItsBorders)
{
  const GPUDisplayParams params = Display(368, 256, 40, 8, 320, 240, 1.33928561f);
  ExpectRects(params, Settings(), 1920, 1080, true, GSVector4i(236, 0, 1683, 1080), GSVector4i(394, 33, 1651, 1045));
  ExpectRects(params, Settings(), 1920, 1080, false, GSVector4i(183, 0, 1736, 1080), GSVector4i(352, 33, 1702, 1045));
}
