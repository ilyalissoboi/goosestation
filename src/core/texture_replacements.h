// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#pragma once

#include "texture_names.h"
#include "texture_pack_config.h"
#include "types.h"

#include "util/image.h"

#include <optional>
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

/// The pack's config (pack_dir), else one a first dump wrote locally (local_dir), over the global settings.
TexturePackConfig LoadGameConfig(const std::string& pack_dir, const std::string& local_dir,
                                 const TexturePackConfig& base);

/// Whether a game folder has a config.yaml or config.yml.
bool HasGameConfig(const std::string& game_dir);

/// Where packs are read: <data folder>/textures through content:// on Android; empty (the default) means
/// EmuFolders::Textures. Takes effect on the next Reload().
void SetPackFolder(std::string folder);

void SetGameID(std::string game_id);

/// Indexes the running game's pack again, and shows the load notice (below) if there is one.
void Reload();

/// What the load notice is worked out from: the game, the switches and renderer in effect, and what its folder holds.
struct LoadNoticeState
{
  std::string_view game_id;
  std::string_view folder; // the game's folder as the notice names it
  bool texture_replacements;
  bool vram_write_replacements;
  bool texture_cache;
  bool hardware_renderer;
  size_t textures;
  size_t vram_writes;
};

/// The notice a load shows: how many replacements apply (with why a pack's textures don't, when only its VRAM writes
/// do), why a pack found doesn't apply (the first reason that fits), or that none was found in its folder. None without
/// a game, or with both replacement switches off.
std::optional<std::string> FormatLoadNotice(const LoadNoticeState& state);

/// The notice for the index as last loaded, with the settings in effect.
std::optional<std::string> GetLoadNotice();

/// Whether the running renderer (the configured one before a GPU exists) can draw replacements.
bool IsUsingHardwareRenderer();

/// Changes with every Reload (a new game, settings, or the reload hotkey).
u32 GetReloadCount();

/// The running game's pack folder, <pack folder>/<serial> (read); its local folder, EmuFolders::Textures/<serial>
/// (written: dumps, a first dump's config); and its dump folder, <local folder>/dumps. Empty with no game.
std::string GetGameDirectory();
std::string GetLocalGameDirectory();
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
