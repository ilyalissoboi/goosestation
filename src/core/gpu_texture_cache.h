// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "gpu_types.h"
#include "texture_names.h"
#include "texture_pack_config.h"
#include "types.h"

#include "util/image.h"

#include "common/gsvector.h"

#include <array>
#include <memory>
#include <optional>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace TextureReplacements {
class ReplacementIndex;
}

/// The hardware renderer's texture cache, CPU side: its own copy of VRAM, which blocks of it are known, the uploads
/// in it (docs/texture-replacement.md, "Tracking VRAM"). It makes no GPU calls.
class GPUTextureCache
{
public:
  static constexpr u32 BLOCK_SIZE = 8; // words
  static constexpr u32 BLOCKS_X = 1024 / BLOCK_SIZE;
  static constexpr u32 BLOCKS_Y = 512 / BLOCK_SIZE;
  static constexpr u32 PAGE_WIDTH = 64;
  static constexpr u32 PAGE_HEIGHT = 256;
  static constexpr u32 NUM_PAGES = (1024 / PAGE_WIDTH) * (512 / PAGE_HEIGHT);

  struct Upload
  {
    GSVector4i rect;
    u64 hash;
    bool hashed;
    u32 splits;
    u64 serial;
  };

  /// What a draw samples: a page, its mode and palette, and whether it's drawn semi-transparent.
  struct DrawKey
  {
    u8 page;
    GPUTextureMode mode;
    u16 palette_bits;
    bool semitransparent;
  };

  /// A replacement placed on its page, in page texels.
  struct Placement
  {
    GSVector4i page_rect;
    std::string path;
    u32 image_width;
    u32 image_height;
  };

  /// A page as drawn with its replacements: its placements, its scale, and an id that changes with the answer.
  struct Source
  {
    DrawKey key;
    std::vector<Placement> placements;
    u32 scale;
    u64 id;
  };

  explicit GPUTextureCache(const TexturePackConfig& config);
  ~GPUTextureCache();

  void SetConfig(const TexturePackConfig& config);

  /// Copies VRAM; every block known, no uploads.
  void Reset(const u16* vram);

  void Write(u32 x, u32 y, u32 width, u32 height, const u16* data, bool set_mask, bool check_mask);
  void Fill(u32 x, u32 y, u32 width, u32 height, u16 color, bool interlaced);
  void Copy(u32 src_x, u32 src_y, u32 dst_x, u32 dst_y, u32 width, u32 height, bool set_mask, bool check_mask);

  /// An area the GPU rendered into: unknown from now on.
  void Draw(GSVector4i rect);

  bool IsKnown(GSVector4i word_rect) const;
  const u16* GetVRAM() const { return m_vram.data(); }

  /// The upload holding the whole rect, its hash computed on first use; nullptr if none does.
  const Upload* FindUpload(GSVector4i word_rect);

  /// Replacements to look up in; nullptr for none.
  void SetIndex(const TextureReplacements::ReplacementIndex* index);

  /// Dumps go to dump_dir (empty: no dumping); the first one creates game_dir's replacements/ and, if write_config
  /// and game_dir has none, its config.yaml.
  void SetDumping(std::string dump_dir, std::string game_dir, bool write_config, bool dump_replaced_textures,
                  bool force_alpha);
  bool IsDumping() const { return !m_dump_dir.empty(); }

  /// A textured draw's rect, in page texels (right and bottom exclusive) with the texture window applied. Rects
  /// collect per run of draws with one key, and merge per texture where they overlap; a texture is dumped when its
  /// upload or page is about to change, or when dumping stops.
  void AddDrawnTexture(const DrawKey& key, GSVector4i texel_rect);

  /// Ends the run and writes the dumps waiting.
  void EndFrame();

  /// Dumps every texture drawn so far, and writes them.
  void FlushDumps();

  /// The replacements to draw a key with; nullptr if none apply. Kept until the key's page or palette changes, at
  /// most MaxHashCacheEntries of them. The pointer is valid until the next call.
  const Source* LookupSource(const DrawKey& key, u32 max_texture_size);
  size_t GetLookupCount() const { return m_lookups.size(); }

  u32 GetPageGeneration(u32 page) const { return m_page_generation[page]; }
  static u32 GetPageX(u32 page) { return (page % (1024 / PAGE_WIDTH)) * PAGE_WIDTH; }
  static u32 GetPageY(u32 page) { return (page / (1024 / PAGE_WIDTH)) * PAGE_HEIGHT; }
  static u32 GetPageIndex(u32 x, u32 y) { return (x / PAGE_WIDTH) + (y / PAGE_HEIGHT) * (1024 / PAGE_WIDTH); }

private:
  using BlockRow = std::array<u64, 2>;
  using BlockMask = std::array<BlockRow, BLOCKS_Y>;

  /// A rect's pieces after wrapping at VRAM's edges (up to four).
  static u32 SplitWrapped(u32 x, u32 y, u32 width, u32 height, std::array<GSVector4i, 4>* pieces);

  static bool SetBlocks(BlockMask& mask, GSVector4i rect, bool whole_blocks_only, bool value);
  static bool AnyBlocks(const BlockMask& mask, GSVector4i rect);

  void Changed(GSVector4i rect);
  void BumpPages(GSVector4i rect);
  void CutUploads(GSVector4i rect);
  void AddUpload(GSVector4i rect);
  void RebuildUploadBlocks();
  void HashUpload(Upload& upload);

  /// The page's width in words for a mode, cut at VRAM's right edge.
  static u32 GetPageWords(u32 page_x, GPUTextureMode mode);
  u64 GetSignature(const DrawKey& key) const;
  bool IsPaletteKnown(const DrawKey& key) const;
  bool HasReplacement(const TextureNames::Name& name, const std::string& formatted) const;
  std::unique_ptr<Source> FindSource(const DrawKey& key, u32 max_texture_size);

  struct PendingDump
  {
    std::string name;
    RGBA8Image image;
  };

  /// A texture being drawn, until its upload or page changes: its key, its upload (upload mode), its rects so far.
  struct DrawnTexture
  {
    DrawKey key;
    u64 upload_serial;
    GSVector4i upload_rect;
    u64 upload_hash;
    std::vector<GSVector4i> rects;
  };

  DrawnTexture& GetDrawnTexture(const DrawKey& key, const Upload* upload);
  void CloseRun();
  void EndDrawnTextures(GSVector4i word_rect);
  bool DrawnTextureEndsWith(const DrawnTexture& texture, GSVector4i word_rect);
  void QueueDumps(const DrawnTexture& texture);
  void QueueDump(const DrawnTexture& texture, GSVector4i texel_rect);
  void WriteDumps();

  struct Lookup
  {
    u64 signature;
    u64 last_used;
    std::unique_ptr<Source> source;
  };

  TexturePackConfig m_config;
  std::vector<u16> m_vram;
  BlockMask m_unknown = {};
  BlockMask m_upload_blocks = {};
  std::vector<Upload> m_uploads;
  std::array<u32, NUM_PAGES> m_page_generation = {};

  const TextureReplacements::ReplacementIndex* m_index = nullptr;

  std::string m_dump_dir;
  std::string m_game_dir;
  bool m_dump_replaced_textures = true;
  bool m_write_config = true;
  bool m_dump_force_alpha = false;
  std::vector<PendingDump> m_pending_dumps;
  std::unordered_set<std::string> m_dumped;
  std::vector<DrawnTexture> m_drawn_textures;
  std::optional<DrawKey> m_run_key;
  std::vector<std::pair<size_t, GSVector4i>> m_run; // drawn texture index, union in this run
  u64 m_next_upload_serial = 0;

  std::unordered_map<u64, Lookup> m_lookups;
  u64 m_lookup_counter = 0;
};
