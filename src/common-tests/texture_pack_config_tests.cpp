// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/texture_pack_config.h"

#include "common/error.h"

#include <gtest/gtest.h>

// The Vagrant Story HD dialogue font pack's config.yaml.
static constexpr const char* FONT_PACK_CONFIG = R"(# UI texture replacements; the release package selects fonts or fonts plus artwork.
DumpTexturePages: true
DumpFullTexturePages: false
DumpTextureWidthThreshold: 1
DumpTextureHeightThreshold: 1
ReducePaletteRange: false
ReplacementScaleLinearFilter: false
)";

TEST(TexturePackConfig, TheDefaultsAreTheReleasesDefaults)
{
  const TexturePackConfig config;
  EXPECT_FALSE(config.dump_texture_pages);
  EXPECT_FALSE(config.dump_full_texture_pages);
  EXPECT_FALSE(config.dump_c16_textures);
  EXPECT_TRUE(config.reduce_palette_range);
  EXPECT_FALSE(config.convert_copies_to_writes);
  EXPECT_FALSE(config.replacement_scale_linear_filter);
  EXPECT_EQ(config.max_vram_write_splits, 0u);
  EXPECT_EQ(config.max_vram_write_coalesce_width, 0u);
  EXPECT_EQ(config.max_vram_write_coalesce_height, 0u);
  EXPECT_EQ(config.texture_dump_width_threshold, 16u);
  EXPECT_EQ(config.texture_dump_height_threshold, 16u);
  EXPECT_EQ(config.vram_write_dump_width_threshold, 128u);
  EXPECT_EQ(config.vram_write_dump_height_threshold, 128u);
  EXPECT_EQ(config.max_hash_cache_entries, 1200u);
  EXPECT_EQ(config.max_hash_cache_vram_usage_mb, 2048u);
  EXPECT_EQ(config.max_replacement_cache_vram_usage_mb, 512u);
  EXPECT_TRUE(config.aliases.empty());
}

TEST(TexturePackConfig, TheFontPacksConfigLoads)
{
  TexturePackConfig config;
  config.max_hash_cache_entries = 77; // not in the file: stays
  Error error;
  ASSERT_TRUE(config.LoadYAML(FONT_PACK_CONFIG, &error)) << error.GetDescription();
  EXPECT_TRUE(config.dump_texture_pages);
  EXPECT_FALSE(config.dump_full_texture_pages);
  EXPECT_EQ(config.texture_dump_width_threshold, 1u);
  EXPECT_EQ(config.texture_dump_height_threshold, 1u);
  EXPECT_FALSE(config.reduce_palette_range);
  EXPECT_FALSE(config.replacement_scale_linear_filter);
  EXPECT_EQ(config.max_hash_cache_entries, 77u);
  EXPECT_EQ(config.max_hash_cache_vram_usage_mb, 2048u);
}

TEST(TexturePackConfig, AliasesLoad)
{
  static constexpr const char* yaml = R"(ReducePaletteRange: true
Aliases:
  texupload-P4-AAAAAAAAAAAAAAAA-BBBBBBBBBBBBBBBB-64x256-0-192-64x64-P0-14: shared.png
  # a comment between entries
  texpage-P8-CCCCCCCCCCCCCCCC-DDDDDDDDDDDDDDDD-128x256-0-0-16x16-P0-255: |
    sub/other.webp
  'texupload-C16-EEEEEEEEEEEEEEEE-64x64-0-0-64x64': "quoted name.png"
MaxHashCacheEntries: 300
)";
  TexturePackConfig config;
  Error error;
  ASSERT_TRUE(config.LoadYAML(yaml, &error)) << error.GetDescription();
  ASSERT_EQ(config.aliases.size(), 3u);
  EXPECT_EQ(config.aliases[0].first, "texupload-P4-AAAAAAAAAAAAAAAA-BBBBBBBBBBBBBBBB-64x256-0-192-64x64-P0-14");
  EXPECT_EQ(config.aliases[0].second, "shared.png");
  EXPECT_EQ(config.aliases[1].first, "texpage-P8-CCCCCCCCCCCCCCCC-DDDDDDDDDDDDDDDD-128x256-0-0-16x16-P0-255");
  EXPECT_EQ(config.aliases[1].second, "sub/other.webp");
  EXPECT_EQ(config.aliases[2].first, "texupload-C16-EEEEEEEEEEEEEEEE-64x64-0-0-64x64");
  EXPECT_EQ(config.aliases[2].second, "quoted name.png");
  EXPECT_EQ(config.max_hash_cache_entries, 300u);
}

TEST(TexturePackConfig, AWrongTypeNamesTheKey)
{
  TexturePackConfig config;
  Error error;
  EXPECT_FALSE(config.LoadYAML("DumpTexturePages: [1]\n", &error));
  EXPECT_NE(error.GetDescription().find("DumpTexturePages"), std::string::npos);

  EXPECT_FALSE(config.LoadYAML("MaxVRAMWriteSplits: -3\n", &error));
  EXPECT_NE(error.GetDescription().find("MaxVRAMWriteSplits"), std::string::npos);
}

TEST(TexturePackConfig, UnknownKeysAndMalformedLinesAreSkipped)
{
  TexturePackConfig config;
  Error error;
  ASSERT_TRUE(config.LoadYAML("SomethingNew: 3\nthis is not yaml\n  : :\nDumpC16Textures: yes\n", &error))
    << error.GetDescription();
  EXPECT_TRUE(config.dump_c16_textures);
}

TEST(TexturePackConfig, WritingThenReadingKeepsEveryValue)
{
  TexturePackConfig config;
  config.dump_texture_pages = true;
  config.dump_full_texture_pages = true;
  config.dump_c16_textures = true;
  config.reduce_palette_range = false;
  config.convert_copies_to_writes = true;
  config.replacement_scale_linear_filter = true;
  config.max_vram_write_splits = 3;
  config.max_vram_write_coalesce_width = 4;
  config.max_vram_write_coalesce_height = 5;
  config.texture_dump_width_threshold = 6;
  config.texture_dump_height_threshold = 7;
  config.vram_write_dump_width_threshold = 8;
  config.vram_write_dump_height_threshold = 9;
  config.max_hash_cache_entries = 10;
  config.max_hash_cache_vram_usage_mb = 11;
  config.max_replacement_cache_vram_usage_mb = 12;
  config.aliases = {{"texpage-P4-AAAAAAAAAAAAAAAA-BBBBBBBBBBBBBBBB-64x256-0-0-16x16-P0-15", "a b.png"}};

  TexturePackConfig loaded;
  Error error;
  ASSERT_TRUE(loaded.LoadYAML(config.ToYAML(), &error)) << error.GetDescription();
  EXPECT_EQ(loaded, config);

  // Our first-dump file has every key commented out, so it changes nothing.
  TexturePackConfig from_template;
  from_template.max_hash_cache_entries = 99;
  ASSERT_TRUE(from_template.LoadYAML(TexturePackConfig::GetTemplateYAML(), &error)) << error.GetDescription();
  TexturePackConfig expected;
  expected.max_hash_cache_entries = 99;
  EXPECT_EQ(from_template, expected);
}

TEST(TexturePackConfig, AByteOrderMarkIsIgnored)
{
  TexturePackConfig config;
  Error error;
  ASSERT_TRUE(config.LoadYAML("\xEF\xBB\xBF" "DumpTexturePages: true\n", &error)) << error.GetDescription();
  EXPECT_TRUE(config.dump_texture_pages);
}
