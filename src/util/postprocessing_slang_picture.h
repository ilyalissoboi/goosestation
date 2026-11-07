// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/gsvector.h"
#include "common/types.h"

#include <algorithm>
#include <utility>

/// The slang stage's picture maths: what the preset is fed, and where its result goes. No GPU here.
namespace PostProcessing::Slang {

struct PictureSize
{
  u32 width;
  u32 height;

  bool operator==(const PictureSize&) const = default;
};

/// The console's picture (the CRTC's active area, no borders), turned when the display is rotated a quarter.
inline PictureSize NativeSize(u32 crtc_vram_width, u32 crtc_vram_height, bool rotated_quarter)
{
  return rotated_quarter ? PictureSize{crtc_vram_height, crtc_vram_width} : PictureSize{crtc_vram_width, crtc_vram_height};
}

/// N when src is exactly N times the native size in both directions and N >= 2: a box filter then averages each
/// N x N block into one console pixel. 0 otherwise (bilinear).
inline u32 BoxFactor(GSVector4i src, PictureSize native)
{
  const s32 w = src.width(), h = src.height();
  if (native.width == 0 || native.height == 0 || w <= 0 || h <= 0)
    return 0;
  const u32 uw = static_cast<u32>(w), uh = static_cast<u32>(h);
  if (uw % native.width != 0 || uh % native.height != 0)
    return 0;
  const u32 factor = uw / native.width;
  return (factor >= 2 && uh / native.height == factor) ? factor : 0;
}

/// The part of rect on a target of the given size; empty when they don't overlap.
inline GSVector4i ClampToTarget(GSVector4i rect, u32 target_width, u32 target_height)
{
  return rect.rintersect(GSVector4i(0, 0, static_cast<s32>(target_width), static_cast<s32>(target_height)));
}

/// The part of a picture the target shows: where it goes (out), the matching part of its source (src) and of the
/// console's picture (native), so a picture larger than the target (integer scaling in a small window) is cropped,
/// keeping its scale, rather than squeezed. src covers the whole picture rect, in its own texture's coordinates.
struct VisiblePicture
{
  GSVector4i out;
  GSVector4i src;
  PictureSize native;
};

inline VisiblePicture CropToTarget(GSVector4i picture, GSVector4i src, PictureSize native, u32 target_width,
                                   u32 target_height)
{
  VisiblePicture v;
  v.out = ClampToTarget(picture, target_width, target_height);
  v.src = src;
  v.native = native;
  if (v.out.rempty() || picture.rempty())
  {
    v.out = GSVector4i::zero();
    return v;
  }
  if (v.out.eq(picture))
    return v;

  const s64 pw = picture.width(), ph = picture.height();
  const s64 sw = src.width(), sh = src.height();
  const auto map_x = [&](s32 x) {
    return static_cast<s32>(src.left + (static_cast<s64>(x - picture.left) * sw) / pw);
  };
  const auto map_y = [&](s32 y) {
    return static_cast<s32>(src.top + (static_cast<s64>(y - picture.top) * sh) / ph);
  };
  v.src = GSVector4i(map_x(v.out.left), map_y(v.out.top), map_x(v.out.right), map_y(v.out.bottom));
  v.native.width = std::max<u32>(1, static_cast<u32>((static_cast<s64>(native.width) * v.out.width()) / pw));
  v.native.height = std::max<u32>(1, static_cast<u32>((static_cast<s64>(native.height) * v.out.height()) / ph));
  return v;
}

/// Whether there's anything to shade: a non-empty rect and a console picture with a size.
inline bool IsUsable(GSVector4i rect, PictureSize native)
{
  return !rect.rempty() && native.width > 0 && native.height > 0;
}

} // namespace PostProcessing::Slang
