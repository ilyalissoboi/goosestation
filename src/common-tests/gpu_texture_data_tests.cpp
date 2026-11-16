// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/gpu_texture_data.h"
#include "core/gpu_types.h"
#include "core/texture_names.h"

#include "xxhash.h"

#include <gtest/gtest.h>

#include <array>
#include <vector>

using namespace GPUTextureData;

namespace {

std::vector<u16> MakeVRAM()
{
  return std::vector<u16>(VRAM_WIDTH * VRAM_HEIGHT, 0);
}

GPUTexturePaletteReg Palette(u32 x, u32 y)
{
  GPUTexturePaletteReg reg = {};
  reg.x = static_cast<u16>(x / 16);
  reg.y = static_cast<u16>(y);
  return reg;
}

u32 Alpha(u32 rgba)
{
  return rgba >> 24;
}

} // namespace

TEST(GPUTextureData, HashesFollowTheRule)
{
  std::vector<u16> vram = MakeVRAM();
  std::vector<u16> packed;
  for (u32 y = 0; y < 256; y++)
  {
    for (u32 x = 0; x < 64; x++)
    {
      const u16 value = static_cast<u16>((y * 64 + x) * 0x9E37u);
      vram[(256 + y) * VRAM_WIDTH + 320 + x] = value;
      packed.push_back(value);
    }
  }

  EXPECT_EQ(HashTextureWords(vram.data(), GSVector4i(320, 256, 384, 512)),
            XXH3_64bits(packed.data(), packed.size() * sizeof(u16)));

  // A 4x16 sub-rect at (330, 300).
  std::vector<u16> sub;
  for (u32 y = 0; y < 16; y++)
    for (u32 x = 0; x < 4; x++)
      sub.push_back(vram[(300 + y) * VRAM_WIDTH + 330 + x]);
  EXPECT_EQ(HashTextureWords(vram.data(), GSVector4i(330, 300, 334, 316)),
            XXH3_64bits(sub.data(), sub.size() * sizeof(u16)));

  // Palette at (768, 227): 0-15 hashes 16 entries; 1-5 hashes the first 5.
  std::array<u16, 16> clut;
  for (u32 i = 0; i < 16; i++)
  {
    clut[i] = static_cast<u16>(0x8000u | (i * 0x421u));
    vram[227 * VRAM_WIDTH + 768 + i] = clut[i];
  }
  EXPECT_EQ(HashPalette(vram.data(), Palette(768, 227), 0, 15), XXH3_64bits(clut.data(), 16 * sizeof(u16)));
  EXPECT_EQ(HashPalette(vram.data(), Palette(768, 227), 1, 5), XXH3_64bits(clut.data(), 5 * sizeof(u16)));
}

TEST(GPUTextureData, AnEightBitPaletteWrapsAtVRAMsRightEdge)
{
  std::vector<u16> vram = MakeVRAM();
  std::vector<u16> clut;
  for (u32 i = 0; i < 256; i++)
  {
    const u16 value = static_cast<u16>(i + 1);
    vram[10 * VRAM_WIDTH + ((960 + i) & 1023)] = value;
    clut.push_back(value);
  }
  EXPECT_EQ(HashPalette(vram.data(), Palette(960, 10), 0, 255), XXH3_64bits(clut.data(), 256 * sizeof(u16)));
}

TEST(GPUTextureData, TheUsedRangeOfATextureIsItsSmallestAndLargestIndex)
{
  std::vector<u16> vram = MakeVRAM();
  // 4-bit page at (64, 0): texels 0..7 of row 0 hold indices 3..10, so words 64 and 65.
  vram[64] = 0x6543;
  vram[65] = 0xA987;
  EXPECT_EQ(GetUsedPaletteRange(vram.data(), 64, 0, GSVector4i(0, 0, 8, 1), GPUTextureMode::Palette4Bit),
            (std::pair<u16, u16>(3, 10)));
  // Only texels 1..2: indices 4 and 5.
  EXPECT_EQ(GetUsedPaletteRange(vram.data(), 64, 0, GSVector4i(1, 0, 3, 1), GPUTextureMode::Palette4Bit),
            (std::pair<u16, u16>(4, 5)));

  // 8-bit page at (128, 256): indices 0 and 200 in one word.
  vram[256 * VRAM_WIDTH + 128] = 0xC800;
  EXPECT_EQ(GetUsedPaletteRange(vram.data(), 128, 256, GSVector4i(0, 0, 2, 1), GPUTextureMode::Palette8Bit),
            (std::pair<u16, u16>(0, 200)));
}

TEST(GPUTextureData, DecodingAP4TextureAppliesItsPalette)
{
  std::vector<u16> vram = MakeVRAM();
  vram[5 * VRAM_WIDTH + 64] = 0x3210; // page (64, 0), row 5: indices 0, 1, 2, 3 (low nibble first)
  for (u32 i = 0; i < 16; i++)
    vram[480 * VRAM_WIDTH + 16 + i] = static_cast<u16>(i == 0 ? 0 : (0x001F | (i << 10)));

  RGBA8Image image;
  DecodeTexture(vram.data(), 64, 0, GSVector4i(1, 5, 4, 6), GPUTextureMode::Palette4Bit, Palette(16, 480),
                Encoding::Source, false, false, &image);
  ASSERT_EQ(image.GetWidth(), 3u);
  ASSERT_EQ(image.GetHeight(), 1u);
  EXPECT_EQ(image.GetPixel(0, 0), TexelToSourceRGBA(0x001F | (1 << 10)));
  EXPECT_EQ(image.GetPixel(2, 0), TexelToSourceRGBA(0x001F | (3 << 10)));
}

TEST(GPUTextureData, DecodingAP8TextureAppliesItsPalette)
{
  std::vector<u16> vram = MakeVRAM();
  vram[0 * VRAM_WIDTH + 130] = 0x0201; // page (128, 0): texels 4, 5 are indices 1, 2
  vram[300 * VRAM_WIDTH + 1] = 0x7C00;
  vram[300 * VRAM_WIDTH + 2] = 0x03E0;

  RGBA8Image image;
  DecodeTexture(vram.data(), 128, 0, GSVector4i(4, 0, 6, 1), GPUTextureMode::Palette8Bit, Palette(0, 300),
                Encoding::Source, false, false, &image);
  EXPECT_EQ(image.GetPixel(0, 0), TexelToSourceRGBA(0x7C00));
  EXPECT_EQ(image.GetPixel(1, 0), TexelToSourceRGBA(0x03E0));
}

TEST(GPUTextureData, DecodingAC16TextureReadsItsWords)
{
  std::vector<u16> vram = MakeVRAM();
  vram[3 * VRAM_WIDTH + 256 + 7] = 0x801F;

  RGBA8Image image;
  DecodeTexture(vram.data(), 256, 0, GSVector4i(7, 3, 8, 4), GPUTextureMode::Direct16Bit, {}, Encoding::Source, false,
                false, &image);
  EXPECT_EQ(image.GetPixel(0, 0), TexelToSourceRGBA(0x801F));
}

TEST(GPUTextureData, SourceAlphaEncodesTransparentAndSemiTransparent)
{
  EXPECT_EQ(TexelToSourceRGBA(0x0000), 0u);
  EXPECT_EQ(Alpha(TexelToSourceRGBA(0x8000)), 0x80u);
  EXPECT_EQ(TexelToSourceRGBA(0x8000) & 0xFFFFFFu, 0u);
  EXPECT_EQ(Alpha(TexelToSourceRGBA(0x7FFF)), 0xFFu);
}

TEST(GPUTextureData, DumpAlphaFollowsTheReferences)
{
  EXPECT_EQ(Alpha(TexelToDumpRGBA(0x0000, false, false)), 0u);
  EXPECT_EQ(Alpha(TexelToDumpRGBA(0x0000, true, false)), 0u);
  EXPECT_EQ(Alpha(TexelToDumpRGBA(0x8000, false, false)), 255u);
  EXPECT_EQ(Alpha(TexelToDumpRGBA(0x8000, true, false)), 143u);
  EXPECT_EQ(TexelToDumpRGBA(0x7C1F, false, false), 0xFFFF00FFu);
  EXPECT_EQ(TexelToDumpRGBA(0x7C1F, true, false), 0xFFFF00FFu);
  EXPECT_EQ(Alpha(TexelToDumpRGBA(0x0000, true, true)), 255u);
  EXPECT_EQ(Alpha(TexelToDumpRGBA(0x8000, true, true)), 255u);
  EXPECT_EQ(TexelToDumpRGBA(3, false, false) & 0xFFu, 25u);
  EXPECT_EQ(TexelToDumpRGBA(16, false, false) & 0xFFu, 132u);
}

TEST(GPUTextureData, AnUploadsRectLandsInItsPage)
{
  TextureNames::Name name = {};
  name.kind = TextureNames::Kind::TexUpload;
  name.mode = TextureNames::Mode::P4;
  name.offset_x = 8;
  name.offset_y = 4;
  name.width = 16;
  name.height = 16;
  // Upload at words (16, 0) of page (0, 0): 64 texels in.
  const std::optional<GSVector4i> rect =
    GetPageRect(name, GSVector4i(16, 0, 32, 64), 0, 0, GPUTextureMode::Palette4Bit);
  ASSERT_TRUE(rect.has_value());
  EXPECT_TRUE(rect->eq(GSVector4i(72, 4, 88, 20)));

  name.offset_x = 250;
  EXPECT_FALSE(GetPageRect(name, GSVector4i(0, 0, 64, 64), 0, 0, GPUTextureMode::Palette4Bit).has_value());
}

TEST(GPUTextureData, APageRectsWordsAreTheWordsUnderIt)
{
  const std::optional<GSVector4i> p4 =
    TexelRectToWordRect(64, 256, GSVector4i(228, 0, 256, 16), GPUTextureMode::Palette4Bit);
  ASSERT_TRUE(p4.has_value());
  EXPECT_TRUE(p4->eq(GSVector4i(121, 256, 128, 272)));
  // Texels 1..6 of a 4-bit page touch words 0 and 1.
  EXPECT_TRUE(TexelRectToWordRect(0, 0, GSVector4i(1, 0, 6, 1), GPUTextureMode::Palette4Bit)->eq(GSVector4i(0, 0, 2, 1)));
  // An 8-bit page at 960 reaches past VRAM's edge.
  EXPECT_FALSE(TexelRectToWordRect(960, 0, GSVector4i(0, 0, 256, 1), GPUTextureMode::Palette8Bit).has_value());
}

TEST(GPUTextureData, TheSourceScaleIsTheLargestThatFits)
{
  using Entry = std::pair<GSVector4i, std::pair<u32, u32>>;
  const std::array<Entry, 2> entries = {
    {{GSVector4i(0, 0, 16, 16), {128, 128}}, {GSVector4i(16, 0, 32, 16), {32, 48}}}};
  EXPECT_EQ(GetSourceScale(entries, 16384), 8u);
  EXPECT_EQ(GetSourceScale(entries, 1024), 4u);

  const std::array<Entry, 1> smaller = {{{GSVector4i(0, 0, 16, 16), {8, 8}}}};
  EXPECT_EQ(GetSourceScale(smaller, 16384), 1u);

  const std::array<Entry, 1> fractional = {{{GSVector4i(0, 0, 16, 16), {56, 56}}}};
  EXPECT_EQ(GetSourceScale(fractional, 16384), 4u);
}
