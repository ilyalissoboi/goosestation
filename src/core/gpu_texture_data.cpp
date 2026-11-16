// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "gpu_texture_data.h"

#include "xxhash.h"

#include <algorithm>
#include <array>

namespace GPUTextureData {

static constexpr u32 PAGE_TEXELS = 256;

/// 5-bit channels to 8 bits, rounded (docs/texture-replacement.md, "Dumped images").
static u32 Expand5(u32 value)
{
  return (value * 255u + 15u) / 31u;
}

static u32 ExpandColour(u16 texel)
{
  return Expand5(texel & 31u) | (Expand5((texel >> 5) & 31u) << 8) | (Expand5((texel >> 10) & 31u) << 16);
}

/// The palette index or direct colour at a page texel.
static u16 GetTexel(const u16* vram, u32 page_x, u32 page_y, u32 x, u32 y, GPUTextureMode mode)
{
  const u32 vy = (page_y + y) & (VRAM_HEIGHT - 1);
  switch (mode)
  {
    case GPUTextureMode::Palette4Bit:
      return (vram[vy * VRAM_WIDTH + ((page_x + x / 4) & (VRAM_WIDTH - 1))] >> ((x % 4) * 4)) & 0xFu;
    case GPUTextureMode::Palette8Bit:
      return (vram[vy * VRAM_WIDTH + ((page_x + x / 2) & (VRAM_WIDTH - 1))] >> ((x % 2) * 8)) & 0xFFu;
    default:
      return vram[vy * VRAM_WIDTH + ((page_x + x) & (VRAM_WIDTH - 1))];
  }
}

} // namespace GPUTextureData

u32 GPUTextureData::GetTexelsPerWord(GPUTextureMode mode)
{
  static constexpr std::array<u32, 4> texels = {{4, 2, 1, 1}};
  return texels[static_cast<u8>(mode)];
}

u64 GPUTextureData::HashTextureWords(const u16* vram, GSVector4i word_rect)
{
  XXH3_state_t state;
  XXH3_64bits_reset(&state);
  const size_t row_bytes = static_cast<size_t>(word_rect.width()) * sizeof(u16);
  for (s32 y = word_rect.top; y < word_rect.bottom; y++)
    XXH3_64bits_update(&state, &vram[y * VRAM_WIDTH + word_rect.left], row_bytes);
  return XXH3_64bits_digest(&state);
}

u64 GPUTextureData::HashPalette(const u16* vram, GPUTexturePaletteReg palette, u16 min, u16 max)
{
  std::array<u16, 256> entries;
  const u32 count = static_cast<u32>(max - min) + 1u;
  const u32 base_x = palette.GetXBase();
  const u32 base_y = palette.GetYBase();
  for (u32 i = 0; i < count; i++)
    entries[i] = vram[base_y * VRAM_WIDTH + ((base_x + i) & (VRAM_WIDTH - 1))];
  return XXH3_64bits(entries.data(), count * sizeof(u16));
}

std::pair<u16, u16> GPUTextureData::GetUsedPaletteRange(const u16* vram, u32 page_x, u32 page_y, GSVector4i texel_rect,
                                                        GPUTextureMode mode)
{
  u16 min = 0xFFFF, max = 0;
  for (s32 y = texel_rect.top; y < texel_rect.bottom; y++)
  {
    for (s32 x = texel_rect.left; x < texel_rect.right; x++)
    {
      const u16 index = GetTexel(vram, page_x, page_y, x, y, mode);
      min = std::min(min, index);
      max = std::max(max, index);
    }
  }
  return (min <= max) ? std::make_pair(min, max) : std::make_pair<u16, u16>(0, 0);
}

std::optional<GSVector4i> GPUTextureData::TexelRectToWordRect(u32 page_x, u32 page_y, GSVector4i texel_rect,
                                                              GPUTextureMode mode)
{
  const s32 tpw = static_cast<s32>(GetTexelsPerWord(mode));
  const GSVector4i words(static_cast<s32>(page_x) + texel_rect.left / tpw, static_cast<s32>(page_y) + texel_rect.top,
                         static_cast<s32>(page_x) + (texel_rect.right + tpw - 1) / tpw,
                         static_cast<s32>(page_y) + texel_rect.bottom);
  if (words.right > static_cast<s32>(VRAM_WIDTH) || words.bottom > static_cast<s32>(VRAM_HEIGHT))
    return std::nullopt;
  return words;
}

u32 GPUTextureData::TexelToSourceRGBA(u16 texel)
{
  if (texel == 0)
    return 0;
  return ExpandColour(texel) | ((texel & 0x8000u) ? 0x80000000u : 0xFF000000u);
}

u32 GPUTextureData::TexelToDumpRGBA(u16 texel, bool semitransparent_mode, bool force_alpha)
{
  u32 alpha;
  if (force_alpha)
    alpha = 255;
  else if (texel == 0)
    alpha = 0;
  else if (semitransparent_mode && (texel & 0x8000u))
    alpha = 143;
  else
    alpha = 255;
  return ExpandColour(texel) | (alpha << 24);
}

void GPUTextureData::DecodeTexture(const u16* vram, u32 page_x, u32 page_y, GSVector4i texel_rect,
                                   GPUTextureMode mode, GPUTexturePaletteReg palette, Encoding encoding,
                                   bool semitransparent_mode, bool force_alpha, RGBA8Image* out)
{
  out->SetSize(texel_rect.width(), texel_rect.height());
  const bool palettized = (mode == GPUTextureMode::Palette4Bit || mode == GPUTextureMode::Palette8Bit);
  const u32 clut_x = palette.GetXBase();
  const u32 clut_y = palette.GetYBase();

  for (s32 y = texel_rect.top; y < texel_rect.bottom; y++)
  {
    for (s32 x = texel_rect.left; x < texel_rect.right; x++)
    {
      u16 texel = GetTexel(vram, page_x, page_y, x, y, mode);
      if (palettized)
        texel = vram[clut_y * VRAM_WIDTH + ((clut_x + texel) & (VRAM_WIDTH - 1))];

      out->SetPixel(x - texel_rect.left, y - texel_rect.top,
                    (encoding == Encoding::Source) ? TexelToSourceRGBA(texel) :
                                                     TexelToDumpRGBA(texel, semitransparent_mode, force_alpha));
    }
  }
}

std::optional<GSVector4i> GPUTextureData::GetPageRect(const TextureNames::Name& name, GSVector4i source_word_rect,
                                                      u32 page_x, u32 page_y, GPUTextureMode mode)
{
  const s32 tpw = static_cast<s32>(GetTexelsPerWord(mode));
  const s32 left = (source_word_rect.left - static_cast<s32>(page_x)) * tpw + name.offset_x;
  const s32 top = (source_word_rect.top - static_cast<s32>(page_y)) + name.offset_y;
  const GSVector4i rect(left, top, left + name.width, top + name.height);
  if (rect.left < 0 || rect.top < 0 || rect.right > static_cast<s32>(PAGE_TEXELS) ||
      rect.bottom > static_cast<s32>(PAGE_TEXELS))
  {
    return std::nullopt;
  }
  return rect;
}

u32 GPUTextureData::GetSourceScale(std::span<const std::pair<GSVector4i, std::pair<u32, u32>>> rects_and_image_sizes,
                                   u32 max_texture_size)
{
  u32 scale = 1;
  for (const auto& [rect, size] : rects_and_image_sizes)
  {
    const u32 rw = std::max<s32>(rect.width(), 1);
    const u32 rh = std::max<s32>(rect.height(), 1);
    scale = std::max({scale, (size.first + rw - 1) / rw, (size.second + rh - 1) / rh});
  }
  return std::clamp<u32>(scale, 1u, std::max<u32>(max_texture_size / PAGE_TEXELS, 1u));
}
