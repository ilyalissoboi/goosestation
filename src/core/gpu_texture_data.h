// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "gpu_types.h"
#include "texture_names.h"
#include "types.h"

#include "util/image.h"

#include "common/gsvector.h"

#include <optional>
#include <span>
#include <utility>

/// Hashing, decoding and placing textures from a copy of VRAM (docs/texture-replacement.md, "Hashes" and "Dumped
/// images"). Every vram argument is VRAM_WIDTH x VRAM_HEIGHT words; rects are left/top/right/bottom.
namespace GPUTextureData {

/// XXH3_64bits over the words of a rect, row after row. The rect must lie within VRAM.
u64 HashTextureWords(const u16* vram, GSVector4i word_rect);

/// XXH3_64bits over max - min + 1 entries from the palette's first entry (wrapping at VRAM's right edge).
u64 HashPalette(const u16* vram, GPUTexturePaletteReg palette, u16 min, u16 max);

/// The smallest and largest palette index the texels of a page rect use.
std::pair<u16, u16> GetUsedPaletteRange(const u16* vram, u32 page_x, u32 page_y, GSVector4i texel_rect,
                                        GPUTextureMode mode);

/// The words under a page rect; std::nullopt where they would pass VRAM's right or bottom edge.
std::optional<GSVector4i> TexelRectToWordRect(u32 page_x, u32 page_y, GSVector4i texel_rect, GPUTextureMode mode);

/// A texel as drawn from a source: 0x0000 transparent (all zero), bit 15 alpha 0x80, otherwise alpha 0xFF.
u32 TexelToSourceRGBA(u16 texel);

/// A texel as dumped: 0x0000 alpha 0; bit 15 in a semi-transparent mode alpha 143; otherwise 255; force_alpha makes
/// every texel 255.
u32 TexelToDumpRGBA(u16 texel, bool semitransparent_mode, bool force_alpha);

enum class Encoding : u8
{
  Source,
  Dump,
};

/// Decodes a page rect, through its palette for palette modes.
void DecodeTexture(const u16* vram, u32 page_x, u32 page_y, GSVector4i texel_rect, GPUTextureMode mode,
                   GPUTexturePaletteReg palette, Encoding encoding, bool semitransparent_mode, bool force_alpha,
                   RGBA8Image* out);

/// Where a name's rect lands in its page, in page texels; std::nullopt outside the 256x256 page.
std::optional<GSVector4i> GetPageRect(const TextureNames::Name& name, GSVector4i source_word_rect, u32 page_x,
                                      u32 page_y, GPUTextureMode mode);

/// The scale a page is drawn at to fit every replacement on it: the largest ceil(image / rect) over both axes, at
/// least 1, at most max_texture_size / 256.
u32 GetSourceScale(std::span<const std::pair<GSVector4i, std::pair<u32, u32>>> rects_and_image_sizes,
                   u32 max_texture_size);

/// Texels in a VRAM word: 4 for 4-bit, 2 for 8-bit, 1 for direct colour.
u32 GetTexelsPerWord(GPUTextureMode mode);

} // namespace GPUTextureData
