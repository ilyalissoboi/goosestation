// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/settings.h"

#include "common/memory_settings_interface.h"

#include <gtest/gtest.h>

TEST(SettingsTextureCache, KeysLoadFromTheirSections)
{
  MemorySettingsInterface si;
  si.SetBoolValue("GPU", "EnableTextureCache", true);
  si.SetBoolValue("TextureReplacements", "EnableTextureReplacements", true);
  si.SetBoolValue("TextureReplacements", "AlwaysTrackUploads", true);
  si.SetBoolValue("TextureReplacements", "DumpTextures", true);
  si.SetBoolValue("TextureReplacements", "DumpReplacedTextures", false);
  si.SetBoolValue("TextureReplacements", "DumpTextureForceAlphaChannel", true);
  si.SetBoolValue("TextureReplacements", "DumpTexturePages", true);
  si.SetBoolValue("TextureReplacements", "ReducePaletteRange", false);
  si.SetIntValue("TextureReplacements", "MaxVRAMWriteSplits", 2);
  si.SetIntValue("TextureReplacements", "DumpTextureWidthThreshold", 8);
  si.SetIntValue("TextureReplacements", "DumpVRAMWriteHeightThreshold", 64);
  si.SetIntValue("TextureReplacements", "MaxReplacementCacheVRAMUsage", 256);

  Settings settings;
  settings.Load(si, si);
  EXPECT_TRUE(settings.gpu_texture_cache);
  const Settings::TextureReplacementSettings& tr = settings.texture_replacements;
  EXPECT_TRUE(tr.enable_texture_replacements);
  EXPECT_TRUE(tr.always_track_uploads);
  EXPECT_TRUE(tr.dump_textures);
  EXPECT_FALSE(tr.dump_replaced_textures);
  EXPECT_TRUE(tr.dump_texture_force_alpha_channel);
  EXPECT_TRUE(tr.config.dump_texture_pages);
  EXPECT_FALSE(tr.config.reduce_palette_range);
  EXPECT_EQ(tr.config.max_vram_write_splits, 2u);
  EXPECT_EQ(tr.config.texture_dump_width_threshold, 8u);
  EXPECT_EQ(tr.config.vram_write_dump_height_threshold, 64u);
  EXPECT_EQ(tr.config.max_replacement_cache_vram_usage_mb, 256u);

  MemorySettingsInterface saved;
  settings.Save(saved, false);
  EXPECT_TRUE(saved.GetBoolValue("GPU", "EnableTextureCache", false));
  EXPECT_TRUE(saved.GetBoolValue("TextureReplacements", "EnableTextureReplacements", false));
  EXPECT_FALSE(saved.GetBoolValue("TextureReplacements", "DumpReplacedTextures", true));
  EXPECT_TRUE(saved.GetBoolValue("TextureReplacements", "DumpTexturePages", false));
  EXPECT_EQ(saved.GetIntValue("TextureReplacements", "MaxVRAMWriteSplits", 0), 2);
  EXPECT_EQ(saved.GetIntValue("TextureReplacements", "MaxReplacementCacheVRAMUsage", 0), 256);
}

TEST(SettingsTextureCache, TheDefaultsAreOff)
{
  MemorySettingsInterface si;
  Settings settings;
  settings.Load(si, si);
  EXPECT_FALSE(settings.gpu_texture_cache);
  EXPECT_FALSE(settings.texture_replacements.enable_texture_replacements);
  EXPECT_FALSE(settings.texture_replacements.dump_textures);
  EXPECT_TRUE(settings.texture_replacements.dump_replaced_textures);
  EXPECT_FALSE(settings.texture_replacements.ShouldTrackTextures(true, true));
  EXPECT_EQ(settings.texture_replacements.config, TexturePackConfig());
}

TEST(SettingsTextureCache, TexturesAreTrackedOnlyWithTheCacheAndAUse)
{
  Settings::TextureReplacementSettings tr;
  EXPECT_FALSE(tr.ShouldTrackTextures(true, true));
  tr.dump_textures = true;
  EXPECT_TRUE(tr.ShouldTrackTextures(true, false));
  EXPECT_FALSE(tr.ShouldTrackTextures(false, true));
  tr.dump_textures = false;
  tr.enable_texture_replacements = true;
  EXPECT_TRUE(tr.ShouldTrackTextures(true, true));
  // Replacements on, but the game has none: nothing to track for.
  EXPECT_FALSE(tr.ShouldTrackTextures(true, false));
  tr.enable_texture_replacements = false;
  tr.always_track_uploads = true;
  EXPECT_TRUE(tr.ShouldTrackTextures(true, false));
}
