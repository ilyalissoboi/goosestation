// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "texture_pack_config.h"
#include "types.h"

#include "common/gsvector.h"

#include <array>
#include <vector>

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

  u32 GetPageGeneration(u32 page) const { return m_page_generation[page]; }
  static u32 GetPageIndex(u32 x, u32 y) { return (x / PAGE_WIDTH) + (y / PAGE_HEIGHT) * (1024 / PAGE_WIDTH); }

private:
  using BlockRow = std::array<u64, 2>;
  using BlockMask = std::array<BlockRow, BLOCKS_Y>;

  /// A rect's pieces after wrapping at VRAM's edges (up to four).
  static u32 SplitWrapped(u32 x, u32 y, u32 width, u32 height, std::array<GSVector4i, 4>* pieces);

  static void SetBlocks(BlockMask& mask, GSVector4i rect, bool whole_blocks_only, bool value);
  static bool AnyBlocks(const BlockMask& mask, GSVector4i rect);

  void Changed(GSVector4i rect);
  void BumpPages(GSVector4i rect);
  void CutUploads(GSVector4i rect);
  void AddUpload(GSVector4i rect);
  void RebuildUploadBlocks();

  TexturePackConfig m_config;
  std::vector<u16> m_vram;
  BlockMask m_unknown = {};
  BlockMask m_upload_blocks = {};
  std::vector<Upload> m_uploads;
  std::array<u32, NUM_PAGES> m_page_generation = {};
};
