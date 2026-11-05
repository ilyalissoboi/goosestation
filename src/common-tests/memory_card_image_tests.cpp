// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/memory_card_image.h"
#include <gtest/gtest.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

using MemoryCardImage::BLOCK_SIZE;
using MemoryCardImage::DataArray;
using MemoryCardImage::FileInfo;
using MemoryCardImage::FRAME_SIZE;

namespace {

// A save of `blocks` blocks filled with `fill`, whose title frame has one icon frame (so EnumerateFiles lists it).
std::vector<u8> MakeSave(u32 blocks, char fill)
{
  std::vector<u8> save(blocks * BLOCK_SIZE, static_cast<u8>(fill));
  save[0] = 'S';
  save[1] = 'C';
  save[2] = 0x11;
  return save;
}

std::unique_ptr<DataArray> FormattedCard()
{
  auto card = std::make_unique<DataArray>();
  MemoryCardImage::Format(card.get());
  return card;
}

std::vector<FileInfo> Files(const DataArray& card)
{
  return MemoryCardImage::EnumerateFiles(card, true);
}

void Write(DataArray* card, std::string_view name, const std::vector<u8>& save)
{
  ASSERT_TRUE(MemoryCardImage::WriteFile(card, name, save, nullptr));
}

} // namespace

TEST(MemoryCardImage, DeletedMultiBlockSaveListsOnceWithItsBlocks)
{
  auto card = FormattedCard();
  Write(card.get(), "BASLUS-00001", MakeSave(3, 'a'));
  ASSERT_TRUE(MemoryCardImage::DeleteFile(card.get(), Files(*card).at(0), false));

  const std::vector<FileInfo> files = Files(*card);
  ASSERT_EQ(files.size(), 1u);
  ASSERT_TRUE(files[0].deleted);
  ASSERT_EQ(files[0].num_blocks, 3u);
  ASSERT_EQ(files[0].filename, "BASLUS-00001");
  ASSERT_EQ(MemoryCardImage::GetFreeBlockCount(*card), 15u);
}

TEST(MemoryCardImage, UndeleteRestoresAMultiBlockSave)
{
  auto card = FormattedCard();
  const std::vector<u8> save = MakeSave(3, 'a');
  Write(card.get(), "BASLUS-00001", save);
  ASSERT_TRUE(MemoryCardImage::DeleteFile(card.get(), Files(*card).at(0), false));
  ASSERT_TRUE(MemoryCardImage::UndeleteFile(card.get(), Files(*card).at(0)));

  const std::vector<FileInfo> files = Files(*card);
  ASSERT_EQ(files.size(), 1u);
  ASSERT_FALSE(files[0].deleted);
  ASSERT_EQ(files[0].num_blocks, 3u);
  std::vector<u8> read;
  ASSERT_TRUE(MemoryCardImage::ReadFile(*card, files[0], &read, nullptr));
  ASSERT_EQ(read, save);
  ASSERT_EQ(MemoryCardImage::GetFreeBlockCount(*card), 12u);
}

TEST(MemoryCardImage, UndeleteRefusedOnceABlockIsReused)
{
  // The core reuses the lowest free block, which takes a deleted save's first block before the rest; another tool may
  // take a middle one, as done here by hand: block 2 becomes another save's only block.
  auto card = FormattedCard();
  Write(card.get(), "BASLUS-00001", MakeSave(3, 'a'));
  ASSERT_TRUE(MemoryCardImage::DeleteFile(card.get(), Files(*card).at(0), false));
  (*card)[2 * FRAME_SIZE] = 0x51;
  const DataArray before = *card;

  const std::vector<FileInfo> files = Files(*card);
  const auto deleted = std::find_if(files.begin(), files.end(), [](const FileInfo& fi) { return fi.deleted; });
  ASSERT_NE(deleted, files.end());
  ASSERT_FALSE(MemoryCardImage::UndeleteFile(card.get(), *deleted));
  ASSERT_TRUE(*card == before);
}

TEST(MemoryCardImage, UndeleteRefusedForABrokenChain)
{
  // As the old DeleteFile() left a save: every block's link cut.
  auto card = FormattedCard();
  Write(card.get(), "BASLUS-00001", MakeSave(3, 'a'));
  ASSERT_TRUE(MemoryCardImage::DeleteFile(card.get(), Files(*card).at(0), false));
  for (u32 block = 1; block <= 3; block++)
  {
    (*card)[block * FRAME_SIZE + 8] = 0xFF;
    (*card)[block * FRAME_SIZE + 9] = 0xFF;
  }
  const DataArray before = *card;

  const std::vector<FileInfo> files = Files(*card);
  ASSERT_EQ(files.size(), 1u);
  ASSERT_FALSE(MemoryCardImage::UndeleteFile(card.get(), files[0]));
  ASSERT_TRUE(*card == before);
}

TEST(MemoryCardImage, ExportThenImportFromMemoryRoundTrips)
{
  auto card = FormattedCard();
  const std::vector<u8> save = MakeSave(2, 'c');
  Write(card.get(), "BESLES-01234", save);

  std::vector<u8> mcs;
  ASSERT_TRUE(MemoryCardImage::ExportSave(*card, Files(*card).at(0), &mcs, nullptr));
  ASSERT_EQ(mcs.size(), FRAME_SIZE + 2 * BLOCK_SIZE);

  auto other = FormattedCard();
  ASSERT_TRUE(MemoryCardImage::ImportSave(other.get(), "save.mcs", mcs, nullptr));
  const std::vector<FileInfo> files = Files(*other);
  ASSERT_EQ(files.size(), 1u);
  ASSERT_EQ(files[0].filename, "BESLES-01234");
  std::vector<u8> read;
  ASSERT_TRUE(MemoryCardImage::ReadFile(*other, files[0], &read, nullptr));
  ASSERT_EQ(read, save);
}

TEST(MemoryCardImage, ImportRawSaveFromMemoryTakesTheFileTitle)
{
  auto card = FormattedCard();
  ASSERT_TRUE(MemoryCardImage::ImportSave(card.get(), "BESLES-01234.bin", MakeSave(1, 'd'), nullptr));
  const std::vector<FileInfo> files = Files(*card);
  ASSERT_EQ(files.size(), 1u);
  ASSERT_EQ(files[0].filename, "BESLES-01234");
}

TEST(MemoryCardImage, ImportSaveFromMemoryRefusesALiveDuplicate)
{
  auto card = FormattedCard();
  Write(card.get(), "BESLES-01234", MakeSave(1, 'e'));
  std::vector<u8> mcs;
  ASSERT_TRUE(MemoryCardImage::ExportSave(*card, Files(*card).at(0), &mcs, nullptr));

  ASSERT_FALSE(MemoryCardImage::ImportSave(card.get(), "save.mcs", mcs, nullptr));
  ASSERT_EQ(Files(*card).size(), 1u);
}

namespace {

// A 3-block save deleted, then its middle block taken by another tool for a live 1-block save "BASLUS-00002": the
// deleted save's links still lead into it.
std::unique_ptr<DataArray> CardWithAReusedMiddleBlock()
{
  auto card = FormattedCard();
  Write(card.get(), "BASLUS-00001", MakeSave(3, 'a'));
  EXPECT_TRUE(MemoryCardImage::DeleteFile(card.get(), Files(*card).at(0), false));

  // Block 2's directory frame: a live save's only block, 8 KiB, no next block, its name.
  u8* df = card->data() + 2 * FRAME_SIZE;
  std::fill_n(df, FRAME_SIZE, u8(0));
  df[0] = 0x51;
  df[5] = 0x20; // file_size = 8192, little-endian
  df[8] = 0xFF;
  df[9] = 0xFF;
  std::memcpy(df + 10, "BASLUS-00002", 12);
  // Its title frame, as a save has one.
  u8* data = card->data() + 2 * BLOCK_SIZE;
  data[0] = 'S';
  data[1] = 'C';
  data[2] = 0x11;
  return card;
}

bool HasLiveSave(const DataArray& card, std::string_view name)
{
  const std::vector<FileInfo> files = Files(card);
  return std::any_of(files.begin(), files.end(), [name](const FileInfo& fi) { return !fi.deleted && fi.filename == name; });
}

} // namespace

TEST(MemoryCardImage, DeletePermanentlyLeavesAReusedBlockAlone)
{
  auto card = CardWithAReusedMiddleBlock();
  ASSERT_TRUE(HasLiveSave(*card, "BASLUS-00002"));
  const std::vector<FileInfo> files = Files(*card);
  const auto deleted = std::find_if(files.begin(), files.end(), [](const FileInfo& fi) { return fi.deleted; });
  ASSERT_NE(deleted, files.end());

  ASSERT_TRUE(MemoryCardImage::DeleteFile(card.get(), *deleted, true));

  ASSERT_TRUE(HasLiveSave(*card, "BASLUS-00002"));
}

TEST(MemoryCardImage, ImportOverADeletedSaveLeavesAReusedBlockAlone)
{
  // An .mcs of a save named as the deleted one, which the import clears first.
  auto source = FormattedCard();
  Write(source.get(), "BASLUS-00001", MakeSave(1, 'b'));
  std::vector<u8> mcs;
  ASSERT_TRUE(MemoryCardImage::ExportSave(*source, Files(*source).at(0), &mcs, nullptr));

  auto card = CardWithAReusedMiddleBlock();
  ASSERT_TRUE(MemoryCardImage::ImportSave(card.get(), "save.mcs", mcs, nullptr));

  ASSERT_TRUE(HasLiveSave(*card, "BASLUS-00002"));
  ASSERT_TRUE(HasLiveSave(*card, "BASLUS-00001"));
}
