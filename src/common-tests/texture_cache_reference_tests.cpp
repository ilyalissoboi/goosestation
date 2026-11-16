// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// The names a DuckStation release dumped from Vagrant Story's opening (docs/texture-replacement.md, "How names and
// hashes work here"), and, with GOOSE_TEST_DATA set, the uploads and palettes our run drew them from.

#include "core/gpu_texture_data.h"
#include "core/gpu_types.h"
#include "core/texture_names.h"

#include "test_data_dir.h"

#include "common/file_system.h"
#include "common/path.h"
#include "common/string_util.h"

#include "fmt/format.h"

#include <gtest/gtest.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <vector>

namespace {

std::vector<std::string> ReadNames(const char* run)
{
  const std::optional<std::string> list = FileSystem::ReadFileToString(
    Path::Combine(GetTestDataDir(), fmt::format("texture-names/SLUS-01040-reference-{}.txt", run)).c_str());
  std::vector<std::string> names;
  if (list.has_value())
  {
    for (const std::string_view line : StringUtil::SplitString(list.value(), '\n'))
      names.emplace_back(line);
  }
  return names;
}

std::vector<u16> ReadWords(const std::string& path)
{
  const std::optional<DynamicHeapArray<u8>> data = FileSystem::ReadBinaryFile(path.c_str());
  std::vector<u16> words(data.has_value() ? data->size() / 2 : 0);
  if (!words.empty())
    std::memcpy(words.data(), data->data(), words.size() * sizeof(u16));
  return words;
}

} // namespace

TEST(TextureCacheReference, EveryReferenceNameRoundTrips)
{
  if (!FileSystem::DirectoryExists(Path::Combine(GetTestDataDir(), "texture-names").c_str()))
    GTEST_SKIP() << "No test data at " << GetTestDataDir();

  u32 count = 0;
  for (const char* run : {"a", "b", "c"})
  {
    for (const std::string& line : ReadNames(run))
    {
      const std::optional<TextureNames::Name> name = TextureNames::Parse(line);
      ASSERT_TRUE(name.has_value()) << line;
      EXPECT_EQ(TextureNames::Format(name.value()), line);
      count++;
    }
  }
  EXPECT_EQ(count, 32u + 32u + 211u);
}

TEST(TextureCacheReference, OurHashesAreTheReferencesHashes)
{
  const char* root = std::getenv("GOOSE_TEST_DATA");
  const std::string fixtures =
    root ? Path::Combine(root, "texture-refs/SLUS-01040/fixtures") : std::string();
  if (fixtures.empty() || !FileSystem::DirectoryExists(fixtures.c_str()))
    GTEST_SKIP() << "Needs GOOSE_TEST_DATA with texture-refs/SLUS-01040/fixtures";

  // Uploads by size, palettes as 256 entries from their first.
  std::map<std::pair<u32, u32>, std::vector<std::vector<u16>>> uploads;
  FileSystem::FindResultsArray files;
  FileSystem::FindFiles(Path::Combine(fixtures, "uploads").c_str(), "*.bin", FILESYSTEM_FIND_FILES, &files);
  for (const FILESYSTEM_FIND_DATA& fd : files)
  {
    u32 w = 0, h = 0;
    ASSERT_EQ(std::sscanf(std::string(Path::GetFileTitle(fd.FileName)).c_str(), "%ux%u-", &w, &h), 2);
    uploads[{w, h}].push_back(ReadWords(fd.FileName));
  }
  std::vector<std::vector<u16>> palettes;
  files.clear();
  FileSystem::FindFiles(Path::Combine(fixtures, "palettes").c_str(), "*.bin", FILESYSTEM_FIND_FILES, &files);
  for (const FILESYSTEM_FIND_DATA& fd : files)
    palettes.push_back(ReadWords(fd.FileName));
  ASSERT_FALSE(uploads.empty());
  ASSERT_FALSE(palettes.empty());

  std::vector<u16> vram(VRAM_WIDTH * VRAM_HEIGHT);
  u32 checked = 0;
  for (const char* run : {"a", "b"})
  {
    for (const std::string& line : ReadNames(run))
    {
      const TextureNames::Name name = TextureNames::Parse(line).value();
      ASSERT_EQ(name.kind, TextureNames::Kind::TexUpload) << line;

      bool texture_found = false;
      for (const std::vector<u16>& upload : uploads[{name.source_width, name.source_height}])
      {
        for (u32 y = 0; y < name.source_height; y++)
          std::memcpy(&vram[y * VRAM_WIDTH], &upload[y * name.source_width], name.source_width * sizeof(u16));
        texture_found |= (GPUTextureData::HashTextureWords(
                            vram.data(), GSVector4i(0, 0, name.source_width, name.source_height)) == name.texture_hash);
      }
      EXPECT_TRUE(texture_found) << "texture hash of " << line;

      bool palette_found = false;
      for (const std::vector<u16>& palette : palettes)
      {
        std::memcpy(&vram[0], palette.data(), std::min<size_t>(palette.size(), 256) * sizeof(u16));
        palette_found |= (GPUTextureData::HashPalette(vram.data(), GPUTexturePaletteReg{0}, name.palette_min,
                                                      name.palette_max) == name.palette_hash);
      }
      EXPECT_TRUE(palette_found) << "palette hash of " << line;
      checked++;
    }
  }
  EXPECT_EQ(checked, 64u);
}
