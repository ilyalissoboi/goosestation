// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "gpu_texture_cache.h"
#include "gpu_texture_data.h"
#include "gpu_types.h"
#include "texture_replacements.h"

#include "common/file_system.h"
#include "common/log.h"
#include "common/path.h"

#include "fmt/format.h"

#include <algorithm>
#include <cstring>

Log_SetChannel(GPUTextureCache);

GPUTextureCache::GPUTextureCache(const TexturePackConfig& config) : m_config(config), m_vram(VRAM_WIDTH * VRAM_HEIGHT)
{
}

GPUTextureCache::~GPUTextureCache()
{
  if (!m_dump_dir.empty())
    FlushDumps();
}

void GPUTextureCache::SetConfig(const TexturePackConfig& config)
{
  m_config = config;
}

void GPUTextureCache::Reset(const u16* vram)
{
  if (!m_drawn_textures.empty())
  {
    CloseRun();
    for (const DrawnTexture& texture : m_drawn_textures)
      QueueDumps(texture);
    m_drawn_textures.clear();
  }

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
          pieces.push_back(Upload{GSVector4i(l, t, r, b), 0, false, splits, ++m_next_upload_serial});
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
      upload.serial = ++m_next_upload_serial; // a new texture: what was drawn from the old one was dumped above
      SetBlocks(m_upload_blocks, rect, false, true);
      return;
    }
  }

  m_uploads.push_back(Upload{rect, 0, false, 0, ++m_next_upload_serial});
  SetBlocks(m_upload_blocks, rect, false, true);
}

void GPUTextureCache::Write(u32 x, u32 y, u32 width, u32 height, const u16* data, bool set_mask, bool check_mask)
{
  if (!m_drawn_textures.empty())
  {
    std::array<GSVector4i, 4> ended;
    for (u32 i = 0, count = SplitWrapped(x, y, width, height, &ended); i < count; i++)
      EndDrawnTextures(ended[i]);
  }

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
  if (!m_drawn_textures.empty())
  {
    std::array<GSVector4i, 4> ended;
    for (u32 i = 0, count = SplitWrapped(x, y, width, height, &ended); i < count; i++)
      EndDrawnTextures(ended[i]);
  }

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

  if (!m_drawn_textures.empty())
  {
    std::array<GSVector4i, 4> ended;
    for (u32 i = 0, count = SplitWrapped(dst_x, dst_y, width, height, &ended); i < count; i++)
      EndDrawnTextures(ended[i]);
  }

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

  if (!m_drawn_textures.empty())
    EndDrawnTextures(rect);
  Changed(rect);
  SetBlocks(m_unknown, rect, false, true);
}

void GPUTextureCache::HashUpload(Upload& upload)
{
  if (!upload.hashed)
  {
    upload.hash = GPUTextureData::HashTextureWords(m_vram.data(), upload.rect);
    upload.hashed = true;
  }
}

const GPUTextureCache::Upload* GPUTextureCache::FindUpload(GSVector4i word_rect)
{
  for (Upload& upload : m_uploads)
  {
    if (word_rect.left >= upload.rect.left && word_rect.top >= upload.rect.top &&
        word_rect.right <= upload.rect.right && word_rect.bottom <= upload.rect.bottom)
    {
      HashUpload(upload);
      return &upload;
    }
  }

  return nullptr;
}

void GPUTextureCache::SetIndex(const TextureReplacements::ReplacementIndex* index)
{
  m_index = index;
  m_lookups.clear();
}

void GPUTextureCache::SetDumping(std::string dump_dir, std::string game_dir, bool dump_replaced_textures,
                                 bool force_alpha)
{
  if (!m_dump_dir.empty())
    FlushDumps();

  m_dump_dir = std::move(dump_dir);
  m_game_dir = std::move(game_dir);
  m_dump_replaced_textures = dump_replaced_textures;
  m_dump_force_alpha = force_alpha;
  m_dumped.clear();
  m_drawn_textures.clear();
  m_run.clear();
  m_run_key.reset();
}

u32 GPUTextureCache::GetPageWords(u32 page_x, GPUTextureMode mode)
{
  static constexpr std::array<u32, 4> words = {{64, 128, 256, 256}};
  return std::min<u32>(words[static_cast<u8>(mode)], VRAM_WIDTH - page_x);
}

bool GPUTextureCache::IsPaletteKnown(const DrawKey& key) const
{
  if (key.mode != GPUTextureMode::Palette4Bit && key.mode != GPUTextureMode::Palette8Bit)
    return true;

  const GPUTexturePaletteReg palette{key.palette_bits};
  const GSVector4i rect = palette.GetRectangle(key.mode);
  return IsKnown(rect.rintersect(GSVector4i(0, 0, VRAM_WIDTH, VRAM_HEIGHT))) &&
         (rect.right <= static_cast<s32>(VRAM_WIDTH) ||
          IsKnown(GSVector4i(0, rect.top, rect.right - VRAM_WIDTH, rect.bottom)));
}

bool GPUTextureCache::HasReplacement(const TextureNames::Name& name, const std::string& formatted) const
{
  if (!m_index)
    return false;

  const std::span<const TextureReplacements::TextureReplacement> candidates =
    (name.kind == TextureNames::Kind::TexUpload) ? m_index->FindUploadReplacements(name.mode, name.texture_hash) :
                                                   m_index->GetPageReplacements(name.mode);
  for (const TextureReplacements::TextureReplacement& candidate : candidates)
  {
    if (TextureNames::Format(candidate.name) == formatted)
      return true;
  }
  return false;
}

static bool SameKey(const GPUTextureCache::DrawKey& lhs, const GPUTextureCache::DrawKey& rhs)
{
  return lhs.page == rhs.page && lhs.mode == rhs.mode && lhs.palette_bits == rhs.palette_bits &&
         lhs.semitransparent == rhs.semitransparent;
}

void GPUTextureCache::AddDrawnTexture(const DrawKey& in_key, GSVector4i texel_rect)
{
  if (m_dump_dir.empty())
    return;

  DrawKey key = in_key;
  if (key.mode == GPUTextureMode::Reserved_Direct16Bit)
    key.mode = GPUTextureMode::Direct16Bit;
  if (key.mode == GPUTextureMode::Direct16Bit)
    key.palette_bits = 0;

  // Any change of key ends the run, whether or not this draw is dumped.
  if (!m_run_key.has_value() || !SameKey(m_run_key.value(), key))
  {
    CloseRun();
    m_run_key = key;
  }

  const GPUTextureMode mode = key.mode;
  if (mode == GPUTextureMode::Direct16Bit && !m_config.dump_c16_textures)
    return;

  const u32 page_x = GetPageX(key.page), page_y = GetPageY(key.page);
  const s32 tpw = static_cast<s32>(GPUTextureData::GetTexelsPerWord(mode));
  const s32 page_texels = std::min<s32>(static_cast<s32>(GetPageWords(page_x, mode)) * tpw, 256);

  // Whole words, within the page.
  texel_rect = GSVector4i((texel_rect.left / tpw) * tpw, texel_rect.top, ((texel_rect.right + tpw - 1) / tpw) * tpw,
                          texel_rect.bottom)
                 .rintersect(GSVector4i(0, 0, page_texels, 256));
  if (m_config.dump_texture_pages && m_config.dump_full_texture_pages)
    texel_rect = GSVector4i(0, 0, page_texels, 256);
  if (texel_rect.rempty())
    return;

  const std::optional<GSVector4i> words = GPUTextureData::TexelRectToWordRect(page_x, page_y, texel_rect, mode);
  if (!words.has_value() || !IsKnown(words.value()) || !IsPaletteKnown(key))
    return;

  const Upload* upload = nullptr;
  if (!m_config.dump_texture_pages)
  {
    if (!(upload = FindUpload(words.value())))
      return;
  }

  DrawnTexture& texture = GetDrawnTexture(key, upload);
  const size_t index = static_cast<size_t>(&texture - m_drawn_textures.data());
  for (auto& [run_index, run_rect] : m_run)
  {
    if (run_index == index)
    {
      run_rect = run_rect.runion(texel_rect);
      return;
    }
  }
  m_run.emplace_back(index, texel_rect);
}

GPUTextureCache::DrawnTexture& GPUTextureCache::GetDrawnTexture(const DrawKey& key, const Upload* upload)
{
  const u64 serial = upload ? upload->serial : 0;
  for (DrawnTexture& texture : m_drawn_textures)
  {
    if (texture.upload_serial == serial && SameKey(texture.key, key))
      return texture;
  }

  DrawnTexture& texture = m_drawn_textures.emplace_back();
  texture.key = key;
  texture.upload_serial = serial;
  texture.upload_rect = upload ? upload->rect : GSVector4i::zero();
  texture.upload_hash = upload ? upload->hash : 0;
  return texture;
}

void GPUTextureCache::CloseRun()
{
  // Each run's rect joins the texture's rects it overlaps (touching isn't enough).
  for (const auto& [index, run_rect] : m_run)
  {
    std::vector<GSVector4i>& rects = m_drawn_textures[index].rects;
    GSVector4i rect = run_rect;
    for (bool merged = true; merged;)
    {
      merged = false;
      for (auto it = rects.begin(); it != rects.end(); ++it)
      {
        if (it->rintersects(rect))
        {
          rect = rect.runion(*it);
          rects.erase(it);
          merged = true;
          break;
        }
      }
    }
    rects.push_back(rect);
  }

  m_run.clear();
}

bool GPUTextureCache::DrawnTextureEndsWith(const DrawnTexture& texture, GSVector4i word_rect)
{
  const u32 page_x = GetPageX(texture.key.page), page_y = GetPageY(texture.key.page);
  const GSVector4i source = texture.upload_serial ?
                              texture.upload_rect :
                              GSVector4i(page_x, page_y, page_x + GetPageWords(page_x, texture.key.mode),
                                         page_y + PAGE_HEIGHT);
  if (source.rintersects(word_rect))
    return true;
  if (texture.key.mode == GPUTextureMode::Direct16Bit)
    return false;

  const GSVector4i palette = GPUTexturePaletteReg{texture.key.palette_bits}.GetRectangle(texture.key.mode);
  return palette.rintersects(word_rect) ||
         (palette.right > static_cast<s32>(VRAM_WIDTH) &&
          GSVector4i(0, palette.top, palette.right - VRAM_WIDTH, palette.bottom).rintersects(word_rect));
}

void GPUTextureCache::EndDrawnTextures(GSVector4i word_rect)
{
  // Most changes are elsewhere: leave the run alone unless a texture ends.
  if (std::none_of(m_drawn_textures.begin(), m_drawn_textures.end(),
                   [&](const DrawnTexture& texture) { return DrawnTextureEndsWith(texture, word_rect); }))
  {
    return;
  }

  CloseRun();
  for (auto it = m_drawn_textures.begin(); it != m_drawn_textures.end();)
  {
    const DrawnTexture& texture = *it;
    if (DrawnTextureEndsWith(texture, word_rect))
    {
      QueueDumps(texture);
      it = m_drawn_textures.erase(it);
    }
    else
    {
      ++it;
    }
  }
}

void GPUTextureCache::QueueDumps(const DrawnTexture& texture)
{
  for (const GSVector4i& rect : texture.rects)
    QueueDump(texture, rect);
}

void GPUTextureCache::QueueDump(const DrawnTexture& texture, GSVector4i texel_rect)
{
  const DrawKey& key = texture.key;
  const GPUTextureMode mode = key.mode;
  const u32 page_x = GetPageX(key.page), page_y = GetPageY(key.page);
  const s32 tpw = static_cast<s32>(GPUTextureData::GetTexelsPerWord(mode));

  TextureNames::Name name = {};
  name.mode = TextureNames::MakeMode(mode, key.semitransparent);
  if (texture.upload_serial)
  {
    // Within the upload.
    const GSVector4i upload_texels((texture.upload_rect.left - static_cast<s32>(page_x)) * tpw,
                                   texture.upload_rect.top - static_cast<s32>(page_y),
                                   (texture.upload_rect.right - static_cast<s32>(page_x)) * tpw,
                                   texture.upload_rect.bottom - static_cast<s32>(page_y));
    texel_rect = texel_rect.rintersect(upload_texels);
    if (texel_rect.rempty())
      return;

    name.kind = TextureNames::Kind::TexUpload;
    name.texture_hash = texture.upload_hash;
    name.source_width = static_cast<u16>(texture.upload_rect.width());
    name.source_height = static_cast<u16>(texture.upload_rect.height());
    name.offset_x = static_cast<u16>(texel_rect.left - upload_texels.left);
    name.offset_y = static_cast<u16>(texel_rect.top - upload_texels.top);
  }
  else
  {
    const std::optional<GSVector4i> words = GPUTextureData::TexelRectToWordRect(page_x, page_y, texel_rect, mode);
    if (!words.has_value())
      return;

    name.kind = TextureNames::Kind::TexPage;
    name.texture_hash = GPUTextureData::HashTextureWords(m_vram.data(), words.value());
    name.source_width = static_cast<u16>(GetPageWords(page_x, mode));
    name.source_height = static_cast<u16>(PAGE_HEIGHT);
    name.offset_x = static_cast<u16>(texel_rect.left);
    name.offset_y = static_cast<u16>(texel_rect.top);
  }

  name.width = static_cast<u16>(texel_rect.width());
  name.height = static_cast<u16>(texel_rect.height());
  if (name.width < m_config.texture_dump_width_threshold || name.height < m_config.texture_dump_height_threshold)
    return;

  const GPUTexturePaletteReg palette{key.palette_bits};
  if (mode != GPUTextureMode::Direct16Bit)
  {
    const std::pair<u16, u16> range =
      m_config.reduce_palette_range ?
        GPUTextureData::GetUsedPaletteRange(m_vram.data(), page_x, page_y, texel_rect, mode) :
        std::pair<u16, u16>(0, (mode == GPUTextureMode::Palette4Bit) ? 15 : 255);
    name.palette_min = range.first;
    name.palette_max = range.second;
    name.palette_hash = GPUTextureData::HashPalette(m_vram.data(), palette, range.first, range.second);
  }

  std::string formatted = TextureNames::Format(name);
  if (!m_dumped.insert(formatted).second)
    return;
  if (!m_dump_replaced_textures && HasReplacement(name, formatted))
    return;

  PendingDump& dump = m_pending_dumps.emplace_back();
  dump.name = std::move(formatted);
  GPUTextureData::DecodeTexture(m_vram.data(), page_x, page_y, texel_rect, mode, palette,
                                GPUTextureData::Encoding::Dump, key.semitransparent, m_dump_force_alpha, &dump.image);
}

void GPUTextureCache::EndFrame()
{
  CloseRun();
  WriteDumps();
}

void GPUTextureCache::FlushDumps()
{
  CloseRun();
  for (const DrawnTexture& texture : m_drawn_textures)
    QueueDumps(texture);
  m_drawn_textures.clear();
  WriteDumps();
}

void GPUTextureCache::WriteDumps()
{
  if (m_pending_dumps.empty() || m_dump_dir.empty())
  {
    m_pending_dumps.clear();
    return;
  }

  if (!FileSystem::EnsureDirectoryExists(m_dump_dir.c_str(), true))
  {
    ERROR_LOG("Can't create '{}'; not dumping.", m_dump_dir);
    m_pending_dumps.clear();
    return;
  }

  if (!m_game_dir.empty())
  {
    FileSystem::EnsureDirectoryExists(Path::Combine(m_game_dir, "replacements").c_str(), false);
    const std::string yaml = Path::Combine(m_game_dir, "config.yaml");
    if (!FileSystem::FileExists(yaml.c_str()) &&
        !FileSystem::FileExists(Path::Combine(m_game_dir, "config.yml").c_str()))
    {
      FileSystem::WriteStringToFile(yaml.c_str(), TexturePackConfig::GetTemplateYAML());
    }
  }

  for (const PendingDump& dump : m_pending_dumps)
  {
    const std::string path = Path::Combine(m_dump_dir, fmt::format("{}.png", dump.name));
    if (FileSystem::FileExists(path.c_str()))
      continue;

    DEV_LOG("Dumping '{}'", dump.name);
    if (!dump.image.SaveToFile(path.c_str()))
      ERROR_LOG("Failed to dump '{}'", path);
  }

  m_pending_dumps.clear();
}

u64 GPUTextureCache::GetSignature(const DrawKey& key) const
{
  const u32 page_x = GetPageX(key.page), page_y = GetPageY(key.page);
  u64 signature = 0;
  const u32 last_x = page_x + GetPageWords(page_x, key.mode) - 1;
  for (u32 x = page_x; x <= last_x; x += PAGE_WIDTH)
    signature += m_page_generation[GetPageIndex(x, page_y)];

  if (key.mode == GPUTextureMode::Palette4Bit || key.mode == GPUTextureMode::Palette8Bit)
  {
    const GSVector4i rect = GPUTexturePaletteReg{key.palette_bits}.GetRectangle(key.mode);
    for (s32 x = rect.left; x < rect.right; x += PAGE_WIDTH)
      signature += m_page_generation[GetPageIndex(static_cast<u32>(x) % VRAM_WIDTH, rect.top)];
    signature += m_page_generation[GetPageIndex(static_cast<u32>(rect.right - 1) % VRAM_WIDTH, rect.top)];
  }

  return signature;
}

const GPUTextureCache::Source* GPUTextureCache::LookupSource(const DrawKey& in_key, u32 max_texture_size)
{
  if (!m_index || m_index->GetTextureCount() == 0)
    return nullptr;

  DrawKey key = in_key;
  if (key.mode == GPUTextureMode::Reserved_Direct16Bit)
    key.mode = GPUTextureMode::Direct16Bit;
  if (key.mode == GPUTextureMode::Direct16Bit)
    key.palette_bits = 0;

  const u64 packed = static_cast<u64>(key.page) | (static_cast<u64>(key.mode) << 5) |
                     (static_cast<u64>(key.semitransparent) << 7) | (static_cast<u64>(key.palette_bits) << 8);
  const u64 signature = GetSignature(key);

  auto it = m_lookups.find(packed);
  if (it != m_lookups.end() && it->second.signature == signature)
  {
    it->second.last_used = ++m_lookup_counter;
    return it->second.source.get();
  }

  std::unique_ptr<Source> source = FindSource(key, max_texture_size);
  if (it == m_lookups.end())
    it = m_lookups.emplace(packed, Lookup{}).first;
  it->second.signature = signature;
  it->second.last_used = ++m_lookup_counter;
  it->second.source = std::move(source);

  const size_t limit = std::max<u32>(m_config.max_hash_cache_entries, 1);
  while (m_lookups.size() > limit)
  {
    auto oldest = m_lookups.end();
    for (auto cur = m_lookups.begin(); cur != m_lookups.end(); ++cur)
    {
      if (cur->first != packed && (oldest == m_lookups.end() || cur->second.last_used < oldest->second.last_used))
        oldest = cur;
    }
    m_lookups.erase(oldest);
  }

  return m_lookups.find(packed)->second.source.get();
}

std::unique_ptr<GPUTextureCache::Source> GPUTextureCache::FindSource(const DrawKey& key, u32 max_texture_size)
{
  if (!IsPaletteKnown(key))
    return {};

  const u32 page_x = GetPageX(key.page), page_y = GetPageY(key.page);
  const u32 page_words = GetPageWords(page_x, key.mode);
  const bool palettized = (key.mode != GPUTextureMode::Direct16Bit);
  const GPUTexturePaletteReg palette{key.palette_bits};
  const TextureNames::Mode mode = TextureNames::MakeMode(key.mode, key.semitransparent);

  auto source = std::make_unique<Source>();
  std::vector<std::pair<GSVector4i, std::pair<u32, u32>>> sizes;
  const auto palette_matches = [&](const TextureNames::Name& name) {
    return !palettized ||
           GPUTextureData::HashPalette(m_vram.data(), palette, name.palette_min, name.palette_max) == name.palette_hash;
  };
  const auto place = [&](GSVector4i rect, const std::string& path) {
    const TextureReplacements::ReplacementImage* image = TextureReplacements::GetTextureImage(path);
    if (!image || image->GetWidth() == 0 || image->GetHeight() == 0)
      return;
    DEV_LOG("Placing '{}' at {},{} of page {}", Path::GetFileTitle(path), rect.left, rect.top, key.page);
    source->placements.push_back(Placement{rect, path, image->GetWidth(), image->GetHeight()});
    sizes.emplace_back(rect, std::make_pair(image->GetWidth(), image->GetHeight()));
  };

  // Upload names: by the hashes of the uploads in the page.
  const GSVector4i page_rect(page_x, page_y, page_x + page_words, page_y + PAGE_HEIGHT);
  for (Upload& upload : m_uploads)
  {
    if (!upload.rect.rintersects(page_rect))
      continue;

    HashUpload(upload);
    for (const TextureReplacements::TextureReplacement& candidate :
         m_index->FindUploadReplacements(mode, upload.hash))
    {
      if (candidate.name.source_width != upload.rect.width() || candidate.name.source_height != upload.rect.height() ||
          !palette_matches(candidate.name))
      {
        continue;
      }

      const std::optional<GSVector4i> rect =
        GPUTextureData::GetPageRect(candidate.name, upload.rect, page_x, page_y, key.mode);
      if (rect.has_value())
        place(rect.value(), candidate.path);
    }
  }

  // Page names: by the hash of the words under each name's rect.
  for (const TextureReplacements::TextureReplacement& candidate : m_index->GetPageReplacements(mode))
  {
    const TextureNames::Name& name = candidate.name;
    if (name.source_width != page_words || name.source_height != PAGE_HEIGHT || !palette_matches(name))
      continue;

    const GSVector4i rect(name.offset_x, name.offset_y, name.offset_x + name.width, name.offset_y + name.height);
    const std::optional<GSVector4i> words = GPUTextureData::TexelRectToWordRect(page_x, page_y, rect, key.mode);
    if (!words.has_value() || !IsKnown(words.value()) ||
        GPUTextureData::HashTextureWords(m_vram.data(), words.value()) != name.texture_hash)
    {
      continue;
    }

    place(rect, candidate.path);
  }

  if (source->placements.empty())
    return {};

  source->key = key;
  source->scale = GPUTextureData::GetSourceScale(sizes, max_texture_size);
  source->id = ++m_next_source_id;
  return source;
}
