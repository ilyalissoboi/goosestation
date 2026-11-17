// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "types.h"

#include <string>
#include <string_view>
#include <utility>
#include <vector>

class Error;
class SettingsInterface;

/// The dumping and replacement options a texture pack's config.yaml can set, with the same names as the global
/// [TextureReplacements] keys (docs/texture-replacement.md, "Folders and settings"). Defaults are the published ones.
struct TexturePackConfig
{
  bool dump_texture_pages = false;
  bool dump_full_texture_pages = false;
  bool dump_c16_textures = false;
  bool reduce_palette_range = true;
  bool convert_copies_to_writes = false;
  bool replacement_scale_linear_filter = false;

  u32 max_vram_write_splits = 0;
  u32 max_vram_write_coalesce_width = 0;
  u32 max_vram_write_coalesce_height = 0;

  u32 texture_dump_width_threshold = 16;
  u32 texture_dump_height_threshold = 16;
  u32 vram_write_dump_width_threshold = 128;
  u32 vram_write_dump_height_threshold = 128;

  u32 max_hash_cache_entries = 1200;
#ifdef __ANDROID__
  // Phones and handhelds have far less memory than the desktops the published defaults suit: ours, smaller.
  u32 max_hash_cache_vram_usage_mb = 512;
  u32 max_replacement_cache_vram_usage_mb = 256;
#else
  u32 max_hash_cache_vram_usage_mb = 2048;
  u32 max_replacement_cache_vram_usage_mb = 512;
#endif

  /// A texture name (no extension) and the file it uses instead, in file order. config.yaml only.
  std::vector<std::pair<std::string, std::string>> aliases;

  bool operator==(const TexturePackConfig&) const = default;

  /// Overlays the keys a config.yaml sets. Unknown keys and lines that aren't "Key: value" are skipped with a warning;
  /// a known key with a value of the wrong type fails, naming the key.
  bool LoadYAML(std::string_view yaml, Error* error);

  /// Every key with its value, and the aliases.
  std::string ToYAML() const;

  /// The file written beside a game's first dump: every key, commented out, so the global settings apply until a pack
  /// author sets them.
  static std::string GetTemplateYAML();

  /// The keys under [TextureReplacements] (no aliases there).
  void Load(const SettingsInterface& si);
  void Save(SettingsInterface& si) const;
};
