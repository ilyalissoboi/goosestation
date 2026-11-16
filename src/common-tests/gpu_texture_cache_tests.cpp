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

// Dumping and lookups.

#include "core/texture_names.h"
#include "core/texture_replacements.h"

#include "common/file_system.h"
#include "common/path.h"

#include "fmt/format.h"

#include <cstdlib>
#include <unistd.h>

namespace {

class TempFolder
{
public:
  TempFolder()
  {
    const char* tmpdir = std::getenv("TMPDIR");
    std::string templ = Path::Combine((tmpdir && *tmpdir) ? tmpdir : "/tmp", "common-tests-texcache-XXXXXX");
    if (mkdtemp(templ.data()))
      m_path = std::move(templ);
  }
  ~TempFolder()
  {
    if (!m_path.empty())
      FileSystem::RecursiveDeleteDirectory(m_path.c_str());
  }
  const std::string& path() const { return m_path; }

private:
  std::string m_path;
};

std::vector<std::string> ListPNGs(const std::string& dir)
{
  FileSystem::FindResultsArray files;
  FileSystem::FindFiles(dir.c_str(), "*.png", FILESYSTEM_FIND_FILES, &files);
  std::vector<std::string> names;
  for (const FILESYSTEM_FIND_DATA& fd : files)
    names.push_back(std::string(Path::GetFileTitle(fd.FileName)));
  std::sort(names.begin(), names.end());
  return names;
}

void WritePNG(const std::string& path, u32 width, u32 height)
{
  FileSystem::EnsureDirectoryExists(std::string(Path::GetDirectory(path)).c_str(), true);
  RGBA8Image image(width, height);
  for (u32 y = 0; y < height; y++)
    for (u32 x = 0; x < width; x++)
      image.SetPixel(x, y, 0xFF0000FFu);
  image.SaveToFile(path.c_str());
}

GPUTexturePaletteReg PaletteAt(u32 x, u32 y)
{
  GPUTexturePaletteReg reg = {};
  reg.x = static_cast<u16>(x / 16);
  reg.y = static_cast<u16>(y);
  return reg;
}

// A cache with a 4-bit upload of 64x64 words at (16, 0) in page 0, indices 1..3 drawn, and a palette at (0, 480).
struct Scene
{
  TempFolder folder;
  std::string game_dir;
  std::string dump_dir;
  TexturePackConfig config;
  std::unique_ptr<GPUTextureCache> cache;
  TextureReplacements::ReplacementIndex index;

  explicit Scene(const TexturePackConfig& config_ = TexturePackConfig()) : config(config_)
  {
    game_dir = Path::Combine(folder.path(), "SLUS-00000");
    dump_dir = Path::Combine(game_dir, "dumps");
    cache = MakeCache(config);
    std::vector<u16> upload(64 * 64);
    for (u32 i = 0; i < upload.size(); i++)
    {
      const u16 n = static_cast<u16>(1 + ((i + i / 64) % 3)); // indices 1..3 in every nibble
      upload[i] = static_cast<u16>(n | (n << 4) | (n << 8) | (n << 12));
    }
    cache->Write(16, 0, 64, 64, upload.data(), false, false);
    std::vector<u16> clut(16);
    for (u32 i = 0; i < 16; i++)
      clut[i] = static_cast<u16>(i == 0 ? 0 : (0x8000u | (i * 0x0421u)));
    cache->Write(0, 480, 16, 1, clut.data(), false, false);
    cache->SetIndex(&index);
  }

  void Dump(bool dump_replaced = true) { cache->SetDumping(dump_dir, game_dir, dump_replaced, false); }

  GPUTextureCache::DrawKey Key(GPUTextureMode mode = GPUTextureMode::Palette4Bit, bool st = false) const
  {
    return GPUTextureCache::DrawKey{0, mode, PaletteAt(0, 480).bits, st};
  }

  // The name the upload's rect would be dumped under.
  std::string UploadName(GSVector4i texel_rect, bool st = false)
  {
    TextureNames::Name name = {};
    name.kind = TextureNames::Kind::TexUpload;
    name.mode = TextureNames::MakeMode(GPUTextureMode::Palette4Bit, st);
    name.texture_hash = cache->FindUpload(GSVector4i(16, 0, 80, 64))->hash;
    name.source_width = 64;
    name.source_height = 64;
    name.offset_x = static_cast<u16>(texel_rect.left - 64);
    name.offset_y = static_cast<u16>(texel_rect.top);
    name.width = static_cast<u16>(texel_rect.width());
    name.height = static_cast<u16>(texel_rect.height());
    const auto [min, max] = config.reduce_palette_range ?
                              GPUTextureData::GetUsedPaletteRange(cache->GetVRAM(), 0, 0, texel_rect,
                                                                  GPUTextureMode::Palette4Bit) :
                              std::pair<u16, u16>(0, 15);
    name.palette_min = min;
    name.palette_max = max;
    name.palette_hash = GPUTextureData::HashPalette(cache->GetVRAM(), PaletteAt(0, 480), min, max);
    return TextureNames::Format(name);
  }
};

} // namespace

TEST(GPUTextureCache, ADrawFromAnUploadIsDumpedOnceAtTheEndOfTheFrame)
{
  Scene scene;
  scene.Dump();
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 96, 32));
  EXPECT_TRUE(ListPNGs(scene.dump_dir).empty());
  scene.cache->EndFrame();
  const std::vector<std::string> dumps = ListPNGs(scene.dump_dir);
  ASSERT_EQ(dumps.size(), 1u);
  EXPECT_EQ(dumps[0], scene.UploadName(GSVector4i(64, 0, 96, 32)));

  RGBA8Image image;
  ASSERT_TRUE(image.LoadFromFile(Path::Combine(scene.dump_dir, dumps[0] + ".png").c_str()));
  EXPECT_EQ(image.GetWidth(), 32u);
  EXPECT_EQ(image.GetHeight(), 32u);

  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 96, 32));
  scene.cache->EndFrame();
  EXPECT_EQ(ListPNGs(scene.dump_dir).size(), 1u);
}

TEST(GPUTextureCache, EachRectDrawnIsItsOwnDump)
{
  Scene scene;
  scene.Dump();
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 96, 32));
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(96, 0, 128, 32));
  scene.cache->AddDrawnTexture(scene.Key(GPUTextureMode::Palette4Bit, true), GSVector4i(64, 0, 96, 32));
  scene.cache->EndFrame();
  EXPECT_EQ(ListPNGs(scene.dump_dir).size(), 3u);
}

TEST(GPUTextureCache, ADrawAcrossTwoUploadsIsNotDumpedInUploadMode)
{
  Scene scene;
  scene.Dump();
  // Texels 0..63 are words 0..15, outside the upload at word 16.
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(32, 0, 96, 16));
  scene.cache->EndFrame();
  EXPECT_TRUE(ListPNGs(scene.dump_dir).empty());
}

TEST(GPUTextureCache, PageModeNamesThePage)
{
  TexturePackConfig config;
  config.dump_texture_pages = true;
  config.reduce_palette_range = false;
  Scene scene(config);
  scene.Dump();
  scene.cache->AddDrawnTexture(scene.Key(GPUTextureMode::Palette4Bit, true), GSVector4i(64, 0, 96, 32));
  scene.cache->EndFrame();
  const std::vector<std::string> dumps = ListPNGs(scene.dump_dir);
  ASSERT_EQ(dumps.size(), 1u);
  const std::optional<TextureNames::Name> name = TextureNames::Parse(dumps[0]);
  ASSERT_TRUE(name.has_value());
  EXPECT_EQ(name->kind, TextureNames::Kind::TexPage);
  EXPECT_EQ(name->mode, TextureNames::Mode::STP4);
  EXPECT_EQ(name->source_width, 64);
  EXPECT_EQ(name->source_height, 256);
  EXPECT_EQ(name->offset_x, 64);
  EXPECT_EQ(name->width, 32);
  EXPECT_EQ(name->palette_max, 15);
  EXPECT_EQ(name->texture_hash, GPUTextureData::HashTextureWords(scene.cache->GetVRAM(), GSVector4i(16, 0, 24, 32)));
}

TEST(GPUTextureCache, FullPagesDumpTheWholePage)
{
  TexturePackConfig config;
  config.dump_texture_pages = true;
  config.dump_full_texture_pages = true;
  Scene scene(config);
  scene.Dump();
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 96, 32));
  scene.cache->EndFrame();
  const std::vector<std::string> dumps = ListPNGs(scene.dump_dir);
  ASSERT_EQ(dumps.size(), 1u);
  const std::optional<TextureNames::Name> name = TextureNames::Parse(dumps[0]);
  EXPECT_EQ(name->offset_x, 0);
  EXPECT_EQ(name->width, 256);
  EXPECT_EQ(name->height, 256);
}

TEST(GPUTextureCache, SmallTexturesBelowTheThresholdAreNotDumped)
{
  Scene scene;
  scene.Dump();
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 79, 32)); // 15 wide
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 96, 15)); // 15 tall
  scene.cache->EndFrame();
  EXPECT_TRUE(ListPNGs(scene.dump_dir).empty());
}

TEST(GPUTextureCache, C16IsDumpedOnlyWhenAsked)
{
  Scene scene;
  scene.Dump();
  scene.cache->AddDrawnTexture(scene.Key(GPUTextureMode::Direct16Bit), GSVector4i(16, 0, 48, 32));
  scene.cache->EndFrame();
  EXPECT_TRUE(ListPNGs(scene.dump_dir).empty());

  TexturePackConfig config;
  config.dump_c16_textures = true;
  Scene c16(config);
  c16.Dump();
  c16.cache->AddDrawnTexture(c16.Key(GPUTextureMode::Direct16Bit), GSVector4i(16, 0, 48, 32));
  c16.cache->EndFrame();
  const std::vector<std::string> dumps = ListPNGs(c16.dump_dir);
  ASSERT_EQ(dumps.size(), 1u);
  EXPECT_EQ(TextureNames::Parse(dumps[0])->mode, TextureNames::Mode::C16);
}

TEST(GPUTextureCache, ReplacedTexturesAreDumpedOnlyWhenAsked)
{
  Scene scene;
  const std::string name = scene.UploadName(GSVector4i(64, 0, 96, 32));
  WritePNG(Path::Combine(scene.game_dir, fmt::format("replacements/{}.png", name)), 64, 64);
  scene.index.Build(scene.game_dir, TexturePackConfig());
  scene.Dump(false);
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 96, 32));
  scene.cache->EndFrame();
  EXPECT_TRUE(ListPNGs(scene.dump_dir).empty());

  scene.Dump(true);
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 96, 32));
  scene.cache->EndFrame();
  EXPECT_EQ(ListPNGs(scene.dump_dir).size(), 1u);
}

TEST(GPUTextureCache, TheFirstDumpWritesAConfig)
{
  Scene scene;
  scene.Dump();
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 96, 32));
  scene.cache->EndFrame();
  const std::optional<std::string> yaml =
    FileSystem::ReadFileToString(Path::Combine(scene.game_dir, "config.yaml").c_str());
  ASSERT_TRUE(yaml.has_value());
  EXPECT_EQ(yaml.value(), TexturePackConfig::GetTemplateYAML());
  EXPECT_TRUE(FileSystem::DirectoryExists(Path::Combine(scene.game_dir, "replacements").c_str()));
}

TEST(GPUTextureCache, AnExistingConfigIsNotOverwritten)
{
  Scene scene;
  FileSystem::EnsureDirectoryExists(scene.game_dir.c_str(), true);
  FileSystem::WriteStringToFile(Path::Combine(scene.game_dir, "config.yml").c_str(), "DumpC16Textures: true\n");
  scene.Dump();
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 96, 32));
  scene.cache->EndFrame();
  EXPECT_FALSE(FileSystem::FileExists(Path::Combine(scene.game_dir, "config.yaml").c_str()));
  EXPECT_EQ(FileSystem::ReadFileToString(Path::Combine(scene.game_dir, "config.yml").c_str()).value(),
            "DumpC16Textures: true\n");
}

TEST(GPUTextureCache, ATextureOnUnknownBlocksIsNeitherDumpedNorLookedUp)
{
  TexturePackConfig config;
  config.dump_texture_pages = true;
  Scene scene(config);
  scene.cache->Draw(GSVector4i(16, 0, 24, 8));
  scene.Dump();
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 96, 32));
  scene.cache->EndFrame();
  EXPECT_TRUE(ListPNGs(scene.dump_dir).empty());

  // Nor with a known texture and an unknown palette.
  Scene palette(config);
  palette.cache->Draw(GSVector4i(0, 480, 8, 481));
  palette.Dump();
  palette.cache->AddDrawnTexture(palette.Key(), GSVector4i(64, 0, 96, 32));
  palette.cache->EndFrame();
  EXPECT_TRUE(ListPNGs(palette.dump_dir).empty());
}

TEST(GPUTextureCache, TheReducedRangeNamesTheUsedIndices)
{
  Scene scene;
  scene.Dump();
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 96, 32));
  scene.cache->EndFrame();
  const std::optional<TextureNames::Name> name = TextureNames::Parse(ListPNGs(scene.dump_dir).at(0));
  EXPECT_EQ(name->palette_min, 1);
  EXPECT_EQ(name->palette_max, 3);
}

TEST(GPUTextureCache, ALookupFindsAReplacementAndPlacesIt)
{
  Scene scene;
  const std::string name = scene.UploadName(GSVector4i(64, 0, 96, 32));
  WritePNG(Path::Combine(scene.game_dir, fmt::format("replacements/{}.png", name)), 128, 128);
  scene.index.Build(scene.game_dir, TexturePackConfig());

  const GPUTextureCache::Source* source = scene.cache->LookupSource(scene.Key(), 16384);
  ASSERT_NE(source, nullptr);
  ASSERT_EQ(source->placements.size(), 1u);
  EXPECT_TRUE(source->placements[0].page_rect.eq(GSVector4i(64, 0, 96, 32)));
  EXPECT_EQ(source->placements[0].image_width, 128u);
  EXPECT_EQ(source->scale, 4u);

  // Only for the palette and mode it was named with.
  EXPECT_EQ(scene.cache->LookupSource(scene.Key(GPUTextureMode::Palette4Bit, true), 16384), nullptr);
}

TEST(GPUTextureCache, APageReplacementIsPlacedOnlyWhereItsRectMatches)
{
  TexturePackConfig config;
  config.dump_texture_pages = true;
  config.reduce_palette_range = false;
  Scene scene(config);
  scene.Dump();
  scene.cache->AddDrawnTexture(scene.Key(), GSVector4i(64, 0, 96, 32));
  scene.cache->EndFrame();
  const std::string name = ListPNGs(scene.dump_dir).at(0);
  WritePNG(Path::Combine(scene.game_dir, fmt::format("replacements/{}.png", name)), 64, 64);
  scene.index.Build(scene.game_dir, config);

  const GPUTextureCache::Source* source = scene.cache->LookupSource(scene.Key(), 16384);
  ASSERT_NE(source, nullptr);
  ASSERT_EQ(source->placements.size(), 1u);
  EXPECT_TRUE(source->placements[0].page_rect.eq(GSVector4i(64, 0, 96, 32)));

  // New words under the rect: no longer the same texture.
  const u16 word = 0x7777;
  scene.cache->Write(20, 4, 1, 1, &word, false, false);
  EXPECT_EQ(scene.cache->LookupSource(scene.Key(), 16384), nullptr);
}

TEST(GPUTextureCache, ALookupWithNoReplacementIsNull)
{
  Scene scene;
  EXPECT_EQ(scene.cache->LookupSource(scene.Key(), 16384), nullptr);
}

TEST(GPUTextureCache, ASourceIsLookedUpAgainAfterItsPageChanges)
{
  Scene scene;
  const std::string name = scene.UploadName(GSVector4i(64, 0, 96, 32));
  WritePNG(Path::Combine(scene.game_dir, fmt::format("replacements/{}.png", name)), 64, 64);
  scene.index.Build(scene.game_dir, TexturePackConfig());

  const GPUTextureCache::Source* first = scene.cache->LookupSource(scene.Key(), 16384);
  ASSERT_NE(first, nullptr);
  const u64 first_id = first->id;
  EXPECT_EQ(scene.cache->LookupSource(scene.Key(), 16384)->id, first_id); // kept

  // Elsewhere in the page, but not touching the upload: looked up again, same texture.
  const u16 word = 0x1234;
  scene.cache->Write(2, 100, 1, 1, &word, false, false);
  const GPUTextureCache::Source* again = scene.cache->LookupSource(scene.Key(), 16384);
  ASSERT_NE(again, nullptr);
  EXPECT_NE(again->id, first_id);

  // Over the upload: it's gone.
  scene.cache->Write(20, 4, 1, 1, &word, false, false);
  EXPECT_EQ(scene.cache->LookupSource(scene.Key(), 16384), nullptr);
}

TEST(GPUTextureCache, APaletteChangeIsANewLookup)
{
  Scene scene;
  const std::string name = scene.UploadName(GSVector4i(64, 0, 96, 32));
  WritePNG(Path::Combine(scene.game_dir, fmt::format("replacements/{}.png", name)), 64, 64);
  scene.index.Build(scene.game_dir, TexturePackConfig());
  ASSERT_NE(scene.cache->LookupSource(scene.Key(), 16384), nullptr);

  const u16 colour = 0x7FFF;
  scene.cache->Write(2, 480, 1, 1, &colour, false, false); // palette entry 2
  EXPECT_EQ(scene.cache->LookupSource(scene.Key(), 16384), nullptr);
}

TEST(GPUTextureCache, TheLookupCacheHoldsAtMostItsLimit)
{
  TexturePackConfig config;
  config.max_hash_cache_entries = 2;
  Scene scene(config);
  const std::string name = scene.UploadName(GSVector4i(64, 0, 96, 32));
  WritePNG(Path::Combine(scene.game_dir, fmt::format("replacements/{}.png", name)), 64, 64);
  scene.index.Build(scene.game_dir, TexturePackConfig());

  const u64 id = scene.cache->LookupSource(scene.Key(), 16384)->id;
  for (u32 page = 1; page <= 2; page++)
    scene.cache->LookupSource(GPUTextureCache::DrawKey{static_cast<u8>(page), GPUTextureMode::Palette4Bit,
                                                       PaletteAt(0, 480).bits, false},
                              16384);
  EXPECT_LE(scene.cache->GetLookupCount(), 2u);
  // The first was dropped, so it's looked up again under a new id.
  EXPECT_NE(scene.cache->LookupSource(scene.Key(), 16384)->id, id);
}
