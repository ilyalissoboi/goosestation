// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/gpu_texture_cache.h"
#include "core/gpu_texture_data.h"
#include "core/gpu_types.h"

#include <gtest/gtest.h>

#include <memory>
#include <vector>

namespace {

std::vector<u16> Words(u32 width, u32 height, u16 first)
{
  std::vector<u16> words(width * height);
  for (u32 i = 0; i < words.size(); i++)
    words[i] = static_cast<u16>(first + i);
  return words;
}

std::unique_ptr<GPUTextureCache> MakeCache(const TexturePackConfig& config = TexturePackConfig())
{
  auto cache = std::make_unique<GPUTextureCache>(config);
  std::vector<u16> vram(VRAM_WIDTH * VRAM_HEIGHT, 0);
  cache->Reset(vram.data());
  return cache;
}

u16 At(const GPUTextureCache& cache, u32 x, u32 y)
{
  return cache.GetVRAM()[y * VRAM_WIDTH + x];
}

} // namespace

TEST(GPUTextureCache, AFreshCacheKnowsAllOfVRAM)
{
  const auto cache = MakeCache();
  EXPECT_TRUE(cache->IsKnown(GSVector4i(0, 0, VRAM_WIDTH, VRAM_HEIGHT)));
}

TEST(GPUTextureCache, ADrawMakesItsBlocksUnknown)
{
  const auto cache = MakeCache();
  cache->Draw(GSVector4i(10, 10, 12, 12));
  EXPECT_FALSE(cache->IsKnown(GSVector4i(10, 10, 11, 11)));
  EXPECT_FALSE(cache->IsKnown(GSVector4i(8, 8, 9, 9)));   // same 8x8 block
  EXPECT_TRUE(cache->IsKnown(GSVector4i(16, 16, 24, 24))); // next block
  EXPECT_TRUE(cache->IsKnown(GSVector4i(0, 0, 8, 8)));
}

TEST(GPUTextureCache, AWriteOverAFullBlockMakesItKnownAgain)
{
  const auto cache = MakeCache();
  cache->Draw(GSVector4i(8, 8, 16, 16));
  const std::vector<u16> data = Words(8, 8, 1);
  cache->Write(8, 8, 8, 8, data.data(), false, false);
  EXPECT_TRUE(cache->IsKnown(GSVector4i(8, 8, 16, 16)));
  EXPECT_EQ(At(*cache, 8, 8), 1);
  EXPECT_EQ(At(*cache, 15, 15), 64);
}

TEST(GPUTextureCache, AWriteOverPartOfADrawnBlockLeavesItUnknown)
{
  const auto cache = MakeCache();
  cache->Draw(GSVector4i(8, 8, 16, 16));
  const std::vector<u16> data = Words(4, 8, 1);
  cache->Write(8, 8, 4, 8, data.data(), false, false);
  EXPECT_FALSE(cache->IsKnown(GSVector4i(8, 8, 12, 16)));
}

TEST(GPUTextureCache, AWriteThatWrapsLandsOnBothEdges)
{
  const auto cache = MakeCache();
  const std::vector<u16> data = Words(16, 16, 1);
  cache->Write(1016, 504, 16, 16, data.data(), false, false);
  EXPECT_EQ(At(*cache, 1016, 504), 1);
  EXPECT_EQ(At(*cache, 0, 504), 9);   // row 0, column 8
  EXPECT_EQ(At(*cache, 1016, 0), 129); // row 8, column 0
  EXPECT_EQ(At(*cache, 7, 7), 256);   // the last word
  EXPECT_EQ(cache->FindUpload(GSVector4i(1016, 504, 1020, 508)), nullptr);
}

TEST(GPUTextureCache, ACheckedWriteSkipsMaskedPixels)
{
  const auto cache = MakeCache();
  const u16 masked[2] = {0x8001, 0x0002};
  cache->Write(100, 100, 2, 1, masked, false, false);
  const u16 data[2] = {0x0011, 0x0012};
  cache->Write(100, 100, 2, 1, data, false, true);
  EXPECT_EQ(At(*cache, 100, 100), 0x8001);
  EXPECT_EQ(At(*cache, 101, 100), 0x0012);
  EXPECT_TRUE(cache->IsKnown(GSVector4i(100, 100, 102, 101)));
}

TEST(GPUTextureCache, ASetMaskWriteSetsBit15)
{
  const auto cache = MakeCache();
  const u16 data[2] = {0x0001, 0x8002};
  cache->Write(200, 0, 2, 1, data, true, false);
  EXPECT_EQ(At(*cache, 200, 0), 0x8001);
  EXPECT_EQ(At(*cache, 201, 0), 0x8002);
}

TEST(GPUTextureCache, AFillIsKnown)
{
  const auto cache = MakeCache();
  cache->Draw(GSVector4i(0, 0, 16, 16));
  cache->Fill(0, 0, 16, 16, 0x1234, false);
  EXPECT_TRUE(cache->IsKnown(GSVector4i(0, 0, 16, 16)));
  EXPECT_EQ(At(*cache, 15, 15), 0x1234);
}

TEST(GPUTextureCache, AnInterlacedFillIsUnknown)
{
  const auto cache = MakeCache();
  cache->Fill(0, 0, 16, 16, 0x1234, true);
  EXPECT_FALSE(cache->IsKnown(GSVector4i(0, 0, 16, 16)));
}

TEST(GPUTextureCache, ACopyThatWrapsReadsAndWritesWrapped)
{
  const auto cache = MakeCache();
  const std::vector<u16> data = Words(4, 2, 1);
  cache->Write(1022, 0, 4, 2, data.data(), false, false); // columns 1022, 1023, 0, 1
  cache->Copy(1022, 0, 510, 511, 4, 2, false, false);     // rows 511 and 0
  EXPECT_EQ(At(*cache, 510, 511), 1);
  EXPECT_EQ(At(*cache, 513, 511), 4);
  EXPECT_EQ(At(*cache, 510, 0), 5);
  EXPECT_EQ(At(*cache, 513, 0), 8);
}

TEST(GPUTextureCache, ACopyFromADrawnAreaIsUnknown)
{
  const auto cache = MakeCache();
  cache->Draw(GSVector4i(0, 0, 8, 8));
  cache->Copy(0, 0, 64, 64, 8, 8, false, false);
  EXPECT_FALSE(cache->IsKnown(GSVector4i(64, 64, 72, 72)));
}

TEST(GPUTextureCache, AnUploadIsFoundByARectInsideIt)
{
  const auto cache = MakeCache();
  const std::vector<u16> data = Words(64, 32, 1);
  cache->Write(320, 256, 64, 32, data.data(), false, false);
  const GPUTextureCache::Upload* upload = cache->FindUpload(GSVector4i(330, 260, 340, 270));
  ASSERT_NE(upload, nullptr);
  EXPECT_TRUE(upload->rect.eq(GSVector4i(320, 256, 384, 288)));
  EXPECT_EQ(upload->hash, GPUTextureData::HashTextureWords(cache->GetVRAM(), upload->rect));
  EXPECT_EQ(cache->FindUpload(GSVector4i(380, 260, 390, 270)), nullptr); // sticks out
}

TEST(GPUTextureCache, ARectAcrossTwoUploadsFindsNone)
{
  const auto cache = MakeCache();
  const std::vector<u16> data = Words(16, 16, 1);
  cache->Write(0, 0, 16, 16, data.data(), false, false);
  cache->Write(16, 0, 16, 16, data.data(), false, false);
  EXPECT_EQ(cache->FindUpload(GSVector4i(8, 0, 24, 8)), nullptr);
  EXPECT_NE(cache->FindUpload(GSVector4i(16, 0, 24, 8)), nullptr);
}

TEST(GPUTextureCache, AnUploadOverwrittenInPartIsForgotten)
{
  const auto cache = MakeCache();
  const std::vector<u16> data = Words(64, 64, 1);
  cache->Write(0, 0, 64, 64, data.data(), false, false);
  cache->Fill(16, 16, 16, 16, 0, false);
  EXPECT_EQ(cache->FindUpload(GSVector4i(0, 0, 8, 8)), nullptr);
}

TEST(GPUTextureCache, SplitsKeepTheRestAsUploads)
{
  TexturePackConfig config;
  config.max_vram_write_splits = 1;
  const auto cache = MakeCache(config);
  const std::vector<u16> data = Words(64, 64, 1);
  cache->Write(0, 0, 64, 64, data.data(), false, false);
  cache->Draw(GSVector4i(24, 24, 40, 40));

  // Above, below, left and right of the hole.
  const GPUTextureCache::Upload* top = cache->FindUpload(GSVector4i(0, 0, 64, 24));
  ASSERT_NE(top, nullptr);
  EXPECT_TRUE(top->rect.eq(GSVector4i(0, 0, 64, 24)));
  EXPECT_EQ(top->hash, GPUTextureData::HashTextureWords(cache->GetVRAM(), top->rect));
  EXPECT_NE(cache->FindUpload(GSVector4i(0, 40, 64, 64)), nullptr);
  EXPECT_NE(cache->FindUpload(GSVector4i(0, 24, 24, 40)), nullptr);
  EXPECT_NE(cache->FindUpload(GSVector4i(40, 24, 64, 40)), nullptr);

  // A second split is past the limit: the piece is forgotten.
  cache->Draw(GSVector4i(0, 0, 4, 4));
  EXPECT_EQ(cache->FindUpload(GSVector4i(8, 8, 16, 16)), nullptr);
}

TEST(GPUTextureCache, StackedWritesCoalesce)
{
  TexturePackConfig config;
  config.max_vram_write_coalesce_width = 64;
  config.max_vram_write_coalesce_height = 256;
  const auto cache = MakeCache(config);
  const std::vector<u16> data = Words(64, 64, 1);
  for (u32 i = 0; i < 4; i++)
    cache->Write(128, i * 64, 64, 64, data.data(), false, false);
  const GPUTextureCache::Upload* upload = cache->FindUpload(GSVector4i(128, 0, 192, 256));
  ASSERT_NE(upload, nullptr);
  EXPECT_TRUE(upload->rect.eq(GSVector4i(128, 0, 192, 256)));
}

TEST(GPUTextureCache, WritesDontCoalesceByDefault)
{
  const auto cache = MakeCache();
  const std::vector<u16> data = Words(64, 64, 1);
  cache->Write(128, 0, 64, 64, data.data(), false, false);
  cache->Write(128, 64, 64, 64, data.data(), false, false);
  EXPECT_EQ(cache->FindUpload(GSVector4i(128, 0, 192, 128)), nullptr);
}

TEST(GPUTextureCache, ACopyOfAnUploadBecomesAnUploadWhenConverting)
{
  TexturePackConfig config;
  config.convert_copies_to_writes = true;
  const auto cache = MakeCache(config);
  const std::vector<u16> data = Words(32, 32, 1);
  cache->Write(0, 0, 32, 32, data.data(), false, false);
  cache->Copy(0, 0, 512, 0, 32, 32, false, false);
  const GPUTextureCache::Upload* upload = cache->FindUpload(GSVector4i(512, 0, 544, 32));
  ASSERT_NE(upload, nullptr);
  EXPECT_EQ(upload->hash, cache->FindUpload(GSVector4i(0, 0, 32, 32))->hash);

  const auto plain = MakeCache();
  plain->Write(0, 0, 32, 32, data.data(), false, false);
  plain->Copy(0, 0, 512, 0, 32, 32, false, false);
  EXPECT_EQ(plain->FindUpload(GSVector4i(512, 0, 544, 32)), nullptr);
}

TEST(GPUTextureCache, AWriteBumpsTheGenerationOfEveryPageItTouches)
{
  const auto cache = MakeCache();
  const u32 page0 = cache->GetPageGeneration(0), page1 = cache->GetPageGeneration(1),
            page2 = cache->GetPageGeneration(2);
  const std::vector<u16> data = Words(16, 4, 1);
  cache->Write(56, 10, 16, 4, data.data(), false, false); // words 56..71: pages 0 and 1
  EXPECT_NE(cache->GetPageGeneration(0), page0);
  EXPECT_NE(cache->GetPageGeneration(1), page1);
  EXPECT_EQ(cache->GetPageGeneration(2), page2);

  // Page 17 is the second row's second page.
  const u32 page17 = cache->GetPageGeneration(17);
  cache->Draw(GSVector4i(64, 256, 65, 257));
  EXPECT_NE(cache->GetPageGeneration(17), page17);
}
