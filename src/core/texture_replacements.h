// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#pragma once

#include "texture_names.h"
#include "texture_pack_config.h"
#include "types.h"

#include "util/image.h"

#include <span>
#include <string>
#include <unordered_map>
#include <vector>

namespace TextureReplacements {

using ReplacementImage = RGBA8Image;

/// A replacement file and the name it replaces (its own, or an alias's).
struct TextureReplacement
{
  TextureNames::Name name;
  std::string path;
};

/// The replacements in a game's folder (docs/texture-replacement.md, "Where replacements are found").
class ReplacementIndex
{
public:
  /// Indexes every image named as a texture or VRAM write under game_dir, recursively, except under dumps/, plus the
  /// config's aliases. Names that can't be right (a rect past their source) are skipped with a warning.
  void Build(const std::string& game_dir, const TexturePackConfig& config);
  void Clear();

  const std::string* FindVRAMWrite(u64 high, u64 low) const;

  /// Upload-mode replacements of one upload: every palette, range and rect.
  std::span<const TextureReplacement> FindUploadReplacements(TextureNames::Mode mode, u64 texture_hash) const;

  /// Page-mode replacements of one mode.
  std::span<const TextureReplacement> GetPageReplacements(TextureNames::Mode mode) const;

  std::span<const TextureReplacement> GetTextures() const { return m_textures; }

  /// Every file the index uses, once each.
  std::vector<std::string> GetAllPaths() const;
  size_t GetTextureCount() const { return m_textures.size(); }
  size_t GetVRAMWriteCount() const { return m_vram_writes.size(); }

private:
  bool AddTexture(const TextureNames::Name& name, std::string path);

  // Sorted by kind, mode and texture hash once built.
  std::vector<TextureReplacement> m_textures;
  std::unordered_map<std::string, std::string> m_vram_writes; // "<high><low>" hex -> path
};

/// The game folder's config.yaml (else config.yml) over the global settings; the global settings alone if there is
/// none, or if it fails to load (logged).
TexturePackConfig LoadGameConfig(const std::string& game_dir, const TexturePackConfig& base);

void SetGameID(std::string game_id);

void Reload();

/// The running game's folder, textures/<serial>, and its dump folder, textures/<serial>/dumps (empty with no game).
std::string GetGameDirectory();
std::string GetTextureDumpDirectory();

/// The running game's settings (its config.yaml over the global ones) and replacements.
const TexturePackConfig& GetConfig();
const ReplacementIndex& GetIndex();

/// A loaded replacement image, kept in a cache bounded by MaxReplacementCacheVRAMUsage (least recently used out
/// first). The pointer is valid until the next call.
const ReplacementImage* GetTextureImage(const std::string& path);

const ReplacementImage* GetVRAMReplacement(u32 width, u32 height, const void* pixels);
void DumpVRAMWrite(u32 width, u32 height, const void* pixels);

void Shutdown();

} // namespace TextureReplacements
