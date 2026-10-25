// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-License-Identifier: (GPL-3.0 OR PolyForm-Strict-1.0.0)

#pragma once

#include <array>
#include <span>
#include <string>
#include <string_view>
#include <vector>

class Error;

namespace MemoryCardImage {
enum : u32
{
  DATA_SIZE = 128 * 1024, // 1mbit
  BLOCK_SIZE = 8192,
  FRAME_SIZE = 128,
  FRAMES_PER_BLOCK = BLOCK_SIZE / FRAME_SIZE,
  NUM_BLOCKS = DATA_SIZE / BLOCK_SIZE,
  NUM_FRAMES = DATA_SIZE / FRAME_SIZE,
  ICON_WIDTH = 16,
  ICON_HEIGHT = 16
};

using DataArray = std::array<u8, DATA_SIZE>;

bool LoadFromFile(DataArray* data, const char* filename, Error* error);
bool SaveToFile(const DataArray& data, const char* filename, Error* error);

void Format(DataArray* data);

struct IconFrame
{
  u32 pixels[ICON_WIDTH * ICON_HEIGHT];
};

struct FileInfo
{
  std::string filename;
  std::string title;
  u32 size;
  u32 first_block;
  u32 num_blocks;
  u16 block_mask; // bit n set for each block n in the save's chain
  bool deleted;

  std::vector<IconFrame> icon_frames;
};

bool IsValid(const DataArray& data);
u32 GetFreeBlockCount(const DataArray& data);
std::vector<FileInfo> EnumerateFiles(const DataArray& data, bool include_deleted);
bool ReadFile(const DataArray& data, const FileInfo& fi, std::vector<u8>* buffer, Error* error);
bool WriteFile(DataArray* data, std::string_view filename, std::span<const u8> buffer, Error* error);
bool DeleteFile(DataArray* data, const FileInfo& fi, bool clear_sectors);
bool UndeleteFile(DataArray* data, const FileInfo& fi);

/// Renames a save that isn't deleted: changes its file name in its first directory frame, the only one holding it.
bool SetFileName(DataArray* data, const FileInfo& fi, std::string_view filename);

/// A save's title as stored (Shift-JIS, zero-padded), and replacing it.
std::span<const u8, 64> GetRawTitle(const DataArray& data, const FileInfo& fi);
void SetTitle(DataArray* data, const FileInfo& fi, std::span<const u8, 64> title_sjis);
bool ImportCard(DataArray* data, const char* filename, Error* error);
bool ImportCard(DataArray* data, const char* filename, std::span<const u8> file_data, Error* error);
bool ExportSave(DataArray* data, const FileInfo& fi, const char* filename, Error* error);
bool ImportSave(DataArray* data, const char* filename, Error* error);
} // namespace MemoryCardImage
