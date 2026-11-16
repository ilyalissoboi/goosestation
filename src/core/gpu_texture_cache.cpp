// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "gpu_texture_cache.h"
#include "gpu_texture_data.h"
#include "gpu_types.h"

#include <algorithm>
#include <cstring>

GPUTextureCache::GPUTextureCache(const TexturePackConfig& config) : m_config(config), m_vram(VRAM_WIDTH * VRAM_HEIGHT)
{
}

GPUTextureCache::~GPUTextureCache() = default;

void GPUTextureCache::SetConfig(const TexturePackConfig& config)
{
  m_config = config;
}

void GPUTextureCache::Reset(const u16* vram)
{
  std::memcpy(m_vram.data(), vram, m_vram.size() * sizeof(u16));
  m_unknown = {};
  m_upload_blocks = {};
  m_uploads.clear();
  for (u32& generation : m_page_generation)
    generation++;
}

u32 GPUTextureCache::SplitWrapped(u32 x, u32 y, u32 width, u32 height, std::array<GSVector4i, 4>* pieces)
{
  x %= VRAM_WIDTH;
  y %= VRAM_HEIGHT;
  const u32 w0 = std::min<u32>(width, VRAM_WIDTH - x), w1 = width - w0;
  const u32 h0 = std::min<u32>(height, VRAM_HEIGHT - y), h1 = height - h0;

  u32 count = 0;
  const auto add = [&](u32 px, u32 py, u32 pw, u32 ph) {
    if (pw > 0 && ph > 0)
      (*pieces)[count++] = GSVector4i(px, py, px + pw, py + ph);
  };
  add(x, y, w0, h0);
  add(0, y, std::min<u32>(w1, VRAM_WIDTH), h0);
  add(x, 0, w0, std::min<u32>(h1, VRAM_HEIGHT));
  add(0, 0, std::min<u32>(w1, VRAM_WIDTH), std::min<u32>(h1, VRAM_HEIGHT));
  return count;
}

void GPUTextureCache::SetBlocks(BlockMask& mask, GSVector4i rect, bool whole_blocks_only, bool value)
{
  s32 bx0, by0, bx1, by1; // inclusive start, exclusive end, in blocks
  if (whole_blocks_only)
  {
    bx0 = (rect.left + BLOCK_SIZE - 1) / BLOCK_SIZE;
    by0 = (rect.top + BLOCK_SIZE - 1) / BLOCK_SIZE;
    bx1 = rect.right / BLOCK_SIZE;
    by1 = rect.bottom / BLOCK_SIZE;
  }
  else
  {
    bx0 = rect.left / BLOCK_SIZE;
    by0 = rect.top / BLOCK_SIZE;
    bx1 = (rect.right + BLOCK_SIZE - 1) / BLOCK_SIZE;
    by1 = (rect.bottom + BLOCK_SIZE - 1) / BLOCK_SIZE;
  }
  if (bx0 >= bx1 || by0 >= by1)
    return;

  BlockRow bits = {};
  for (s32 bx = bx0; bx < bx1; bx++)
    bits[bx / 64] |= u64(1) << (bx % 64);

  for (s32 by = by0; by < by1; by++)
  {
    for (u32 i = 0; i < 2; i++)
      mask[by][i] = value ? (mask[by][i] | bits[i]) : (mask[by][i] & ~bits[i]);
  }
}

bool GPUTextureCache::AnyBlocks(const BlockMask& mask, GSVector4i rect)
{
  const s32 bx0 = rect.left / BLOCK_SIZE, by0 = rect.top / BLOCK_SIZE;
  const s32 bx1 = (rect.right + BLOCK_SIZE - 1) / BLOCK_SIZE, by1 = (rect.bottom + BLOCK_SIZE - 1) / BLOCK_SIZE;
  BlockRow bits = {};
  for (s32 bx = bx0; bx < bx1; bx++)
    bits[bx / 64] |= u64(1) << (bx % 64);

  for (s32 by = by0; by < by1; by++)
  {
    if ((mask[by][0] & bits[0]) | (mask[by][1] & bits[1]))
      return true;
  }
  return false;
}

bool GPUTextureCache::IsKnown(GSVector4i word_rect) const
{
  return !AnyBlocks(m_unknown, word_rect.rintersect(GSVector4i(0, 0, VRAM_WIDTH, VRAM_HEIGHT)));
}

void GPUTextureCache::BumpPages(GSVector4i rect)
{
  for (s32 py = rect.top / PAGE_HEIGHT; py <= (rect.bottom - 1) / static_cast<s32>(PAGE_HEIGHT); py++)
  {
    for (s32 px = rect.left / PAGE_WIDTH; px <= (rect.right - 1) / static_cast<s32>(PAGE_WIDTH); px++)
      m_page_generation[py * (1024 / PAGE_WIDTH) + px]++;
  }
}

void GPUTextureCache::Changed(GSVector4i rect)
{
  BumpPages(rect);
  CutUploads(rect);
}

void GPUTextureCache::CutUploads(GSVector4i rect)
{
  if (m_uploads.empty() || !AnyBlocks(m_upload_blocks, rect))
    return;

  std::vector<Upload> pieces;
  bool removed = false;
  for (auto it = m_uploads.begin(); it != m_uploads.end();)
  {
    if (!it->rect.rintersects(rect))
    {
      ++it;
      continue;
    }

    // The upload's untouched remainder, while splits are allowed.
    if (it->splits < m_config.max_vram_write_splits)
    {
      const GSVector4i u = it->rect;
      const GSVector4i hole = rect.rintersect(u);
      const u32 splits = it->splits + 1;
      const auto keep = [&](s32 l, s32 t, s32 r, s32 b) {
        if (l < r && t < b)
          pieces.push_back(Upload{GSVector4i(l, t, r, b), 0, false, splits});
      };
      keep(u.left, u.top, u.right, hole.top);
      keep(u.left, hole.bottom, u.right, u.bottom);
      keep(u.left, hole.top, hole.left, hole.bottom);
      keep(hole.right, hole.top, u.right, hole.bottom);
    }

    it = m_uploads.erase(it);
    removed = true;
  }

  m_uploads.insert(m_uploads.end(), pieces.begin(), pieces.end());
  if (removed)
    RebuildUploadBlocks();
}

void GPUTextureCache::RebuildUploadBlocks()
{
  m_upload_blocks = {};
  for (const Upload& upload : m_uploads)
    SetBlocks(m_upload_blocks, upload.rect, false, true);
}

void GPUTextureCache::AddUpload(GSVector4i rect)
{
  // Join the upload directly above with the same columns, while the result fits.
  for (Upload& upload : m_uploads)
  {
    if (upload.rect.left == rect.left && upload.rect.right == rect.right && upload.rect.bottom == rect.top &&
        static_cast<u32>(rect.width()) <= m_config.max_vram_write_coalesce_width &&
        static_cast<u32>(rect.bottom - upload.rect.top) <= m_config.max_vram_write_coalesce_height)
    {
      upload.rect.bottom = rect.bottom;
      upload.hashed = false;
      SetBlocks(m_upload_blocks, rect, false, true);
      return;
    }
  }

  m_uploads.push_back(Upload{rect, 0, false, 0});
  SetBlocks(m_upload_blocks, rect, false, true);
}

void GPUTextureCache::Write(u32 x, u32 y, u32 width, u32 height, const u16* data, bool set_mask, bool check_mask)
{
  const u16 mask_or = set_mask ? 0x8000 : 0;
  for (u32 row = 0; row < height; row++)
  {
    u16* dst_row = &m_vram[((y + row) % VRAM_HEIGHT) * VRAM_WIDTH];
    const u16* src_row = &data[row * width];
    for (u32 col = 0; col < width; col++)
    {
      u16& dst = dst_row[(x + col) % VRAM_WIDTH];
      if (!check_mask || !(dst & 0x8000))
        dst = src_row[col] | mask_or;
    }
  }

  std::array<GSVector4i, 4> pieces;
  const u32 count = SplitWrapped(x, y, width, height, &pieces);
  for (u32 i = 0; i < count; i++)
  {
    Changed(pieces[i]);
    if (!check_mask)
      SetBlocks(m_unknown, pieces[i], true, false);
  }

  if (count == 1 && !check_mask)
    AddUpload(pieces[0]);
}

void GPUTextureCache::Fill(u32 x, u32 y, u32 width, u32 height, u16 color, bool interlaced)
{
  for (u32 row = 0; row < height; row++)
  {
    u16* dst_row = &m_vram[((y + row) % VRAM_HEIGHT) * VRAM_WIDTH];
    for (u32 col = 0; col < width; col++)
      dst_row[(x + col) % VRAM_WIDTH] = color;
  }

  std::array<GSVector4i, 4> pieces;
  const u32 count = SplitWrapped(x, y, width, height, &pieces);
  for (u32 i = 0; i < count; i++)
  {
    Changed(pieces[i]);
    if (interlaced)
      SetBlocks(m_unknown, pieces[i], false, true);
    else
      SetBlocks(m_unknown, pieces[i], true, false);
  }
}

void GPUTextureCache::Copy(u32 src_x, u32 src_y, u32 dst_x, u32 dst_y, u32 width, u32 height, bool set_mask,
                           bool check_mask)
{
  std::array<GSVector4i, 4> src_pieces;
  const u32 src_count = SplitWrapped(src_x, src_y, width, height, &src_pieces);
  bool src_known = true;
  for (u32 i = 0; i < src_count; i++)
    src_known &= IsKnown(src_pieces[i]);

  // Through a copy of the source, so overlapping copies read what was there before.
  std::vector<u16> src(static_cast<size_t>(width) * height);
  for (u32 row = 0; row < height; row++)
  {
    for (u32 col = 0; col < width; col++)
      src[row * width + col] = m_vram[((src_y + row) % VRAM_HEIGHT) * VRAM_WIDTH + (src_x + col) % VRAM_WIDTH];
  }

  const Upload* src_upload = (m_config.convert_copies_to_writes && src_count == 1 && !set_mask && !check_mask) ?
                               FindUpload(src_pieces[0]) :
                               nullptr;
  const bool convert = (src_upload != nullptr);

  const u16 mask_or = set_mask ? 0x8000 : 0;
  for (u32 row = 0; row < height; row++)
  {
    u16* dst_row = &m_vram[((dst_y + row) % VRAM_HEIGHT) * VRAM_WIDTH];
    for (u32 col = 0; col < width; col++)
    {
      u16& dst = dst_row[(dst_x + col) % VRAM_WIDTH];
      if (!check_mask || !(dst & 0x8000))
        dst = src[row * width + col] | mask_or;
    }
  }

  std::array<GSVector4i, 4> dst_pieces;
  const u32 dst_count = SplitWrapped(dst_x, dst_y, width, height, &dst_pieces);
  for (u32 i = 0; i < dst_count; i++)
  {
    Changed(dst_pieces[i]);
    if (!src_known)
      SetBlocks(m_unknown, dst_pieces[i], false, true);
    else if (!check_mask)
      SetBlocks(m_unknown, dst_pieces[i], true, false);
  }

  if (convert && dst_count == 1)
    AddUpload(dst_pieces[0]);
}

void GPUTextureCache::Draw(GSVector4i rect)
{
  rect = rect.rintersect(GSVector4i(0, 0, VRAM_WIDTH, VRAM_HEIGHT));
  if (rect.rempty())
    return;

  Changed(rect);
  SetBlocks(m_unknown, rect, false, true);
}

const GPUTextureCache::Upload* GPUTextureCache::FindUpload(GSVector4i word_rect)
{
  for (Upload& upload : m_uploads)
  {
    if (word_rect.left >= upload.rect.left && word_rect.top >= upload.rect.top &&
        word_rect.right <= upload.rect.right && word_rect.bottom <= upload.rect.bottom)
    {
      if (!upload.hashed)
      {
        upload.hash = GPUTextureData::HashTextureWords(m_vram.data(), upload.rect);
        upload.hashed = true;
      }
      return &upload;
    }
  }

  return nullptr;
}
