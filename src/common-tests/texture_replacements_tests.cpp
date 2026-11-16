// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/texture_replacements.h"

#include "common/file_system.h"
#include "common/path.h"

#include "fmt/format.h"

#include <gtest/gtest.h>

#include <cstdlib>
#include <string>
#include <unistd.h>

using namespace TextureReplacements;
using TextureNames::Mode;

namespace {

class TempGameFolder
{
public:
  TempGameFolder()
  {
    const char* tmpdir = std::getenv("TMPDIR");
    std::string templ = Path::Combine((tmpdir && *tmpdir) ? tmpdir : "/tmp", "common-tests-textures-XXXXXX");
    if (mkdtemp(templ.data()))
      m_path = std::move(templ);
  }
  ~TempGameFolder()
  {
    if (!m_path.empty())
      FileSystem::RecursiveDeleteDirectory(m_path.c_str());
  }

  const std::string& path() const { return m_path; }

  std::string Write(std::string_view relative, std::string_view contents = "x") const
  {
    const std::string file = Path::Combine(m_path, relative);
    FileSystem::EnsureDirectoryExists(std::string(Path::GetDirectory(file)).c_str(), true);
    FileSystem::WriteStringToFile(file.c_str(), contents);
    return file;
  }

private:
  std::string m_path;
};

constexpr const char* PAGE_NAME = "texpage-STP4-065D13C54C34C0FB-69491D7E26769824-64x256-228-0-28x16-P0-15";
constexpr const char* UPLOAD_NAME = "texupload-P4-B5E04041144CABCE-D7ADDCD353C6521A-64x256-96-192-32x63-P0-15";
constexpr const char* UPLOAD_NAME_2 = "texupload-P4-B5E04041144CABCE-0301D3334C8EE379-64x256-0-192-32x63-P0-15";
constexpr const char* VRAM_WRITE_NAME = "vram-write-000000001234567800000000000000AB";

} // namespace

TEST(TextureReplacementIndex, AGameFolderIsIndexed)
{
  TempGameFolder game;
  game.Write(fmt::format("replacements/{}.png", PAGE_NAME));
  game.Write(fmt::format("sub/{}.webp", UPLOAD_NAME));
  game.Write(fmt::format("{}.jpg", VRAM_WRITE_NAME));
  game.Write("notes.txt");
  game.Write(fmt::format("replacements/{}.txt", UPLOAD_NAME_2));

  ReplacementIndex index;
  index.Build(game.path(), TexturePackConfig());
  EXPECT_EQ(index.GetTextureCount(), 2u);
  EXPECT_EQ(index.GetVRAMWriteCount(), 1u);

  const std::span<const TextureReplacement> pages = index.GetPageReplacements(Mode::STP4);
  ASSERT_EQ(pages.size(), 1u);
  EXPECT_EQ(TextureNames::Format(pages[0].name), PAGE_NAME);
  EXPECT_EQ(Path::GetFileName(pages[0].path), fmt::format("{}.png", PAGE_NAME));
  EXPECT_TRUE(index.GetPageReplacements(Mode::P4).empty());

  const std::span<const TextureReplacement> uploads = index.FindUploadReplacements(Mode::P4, 0xB5E04041144CABCEull);
  ASSERT_EQ(uploads.size(), 1u);
  EXPECT_EQ(Path::GetFileName(uploads[0].path), fmt::format("{}.webp", UPLOAD_NAME));
  EXPECT_TRUE(index.FindUploadReplacements(Mode::STP4, 0xB5E04041144CABCEull).empty());

  const std::string* vram_write = index.FindVRAMWrite(0x0000000012345678ull, 0x00000000000000ABull);
  ASSERT_NE(vram_write, nullptr);
  EXPECT_EQ(Path::GetFileName(*vram_write), fmt::format("{}.jpg", VRAM_WRITE_NAME));
}

TEST(TextureReplacementIndex, DumpsAreNeverLoadedBack)
{
  TempGameFolder game;
  game.Write(fmt::format("dumps/{}.png", UPLOAD_NAME));
  game.Write(fmt::format("dumps/old/{}.png", VRAM_WRITE_NAME));

  ReplacementIndex index;
  index.Build(game.path(), TexturePackConfig());
  EXPECT_EQ(index.GetTextureCount(), 0u);
  EXPECT_EQ(index.GetVRAMWriteCount(), 0u);
}

TEST(TextureReplacementIndex, AnAliasPointsANameAtAFile)
{
  TempGameFolder game;
  game.Write("replacements/shared.png");
  TexturePackConfig config;
  config.aliases = {{PAGE_NAME, "replacements/shared.png"}};

  ReplacementIndex index;
  index.Build(game.path(), config);
  const std::span<const TextureReplacement> pages = index.GetPageReplacements(Mode::STP4);
  ASSERT_EQ(pages.size(), 1u);
  EXPECT_EQ(pages[0].path, Path::Combine(game.path(), "replacements/shared.png"));
}

TEST(TextureReplacementIndex, AnAliasToAMissingFileIsSkipped)
{
  TempGameFolder game;
  TexturePackConfig config;
  config.aliases = {{PAGE_NAME, "replacements/missing.png"}, {"not-a-name", "replacements/missing.png"}};

  ReplacementIndex index;
  index.Build(game.path(), config);
  EXPECT_EQ(index.GetTextureCount(), 0u);
}

TEST(TextureReplacementIndex, ARectPastTheSourceIsSkipped)
{
  TempGameFolder game;
  // A 64-word 4-bit source is 256 texels wide; 250 + 16 is past it.
  game.Write("replacements/texupload-P4-B5E04041144CABCE-D7ADDCD353C6521A-64x256-250-0-16x16-P0-15.png");
  // An 8-bit page is 128 words, 256 texels; 0 + 257 is past it.
  game.Write("replacements/texpage-P8-B5E04041144CABCE-D7ADDCD353C6521A-128x256-0-0-257x16-P0-255.png");
  // 240 + 16 rows of a 256-row source fits.
  game.Write("replacements/texupload-P4-B5E04041144CABCE-D7ADDCD353C6521A-64x256-0-240-16x16-P0-15.png");

  ReplacementIndex index;
  index.Build(game.path(), TexturePackConfig());
  EXPECT_EQ(index.GetTextureCount(), 1u);
}

TEST(TextureReplacementIndex, TwoRectsOfOneTextureAreBothFound)
{
  TempGameFolder game;
  game.Write(fmt::format("replacements/{}.png", UPLOAD_NAME));
  game.Write(fmt::format("replacements/{}.png", UPLOAD_NAME_2));
  game.Write("replacements/texupload-P4-0000000000000001-0301D3334C8EE379-64x256-0-192-32x63-P0-15.png");

  ReplacementIndex index;
  index.Build(game.path(), TexturePackConfig());
  EXPECT_EQ(index.FindUploadReplacements(Mode::P4, 0xB5E04041144CABCEull).size(), 2u);
  EXPECT_EQ(index.FindUploadReplacements(Mode::P4, 1).size(), 1u);
}

TEST(TextureReplacementIndex, AConfigYmlIsReadWhenThereIsNoYaml)
{
  TempGameFolder game;
  game.Write("config.yml", "DumpTexturePages: true\n");
  TexturePackConfig base;
  base.max_hash_cache_entries = 5;
  const TexturePackConfig config = LoadGameConfig(game.path(), base);
  EXPECT_TRUE(config.dump_texture_pages);
  EXPECT_EQ(config.max_hash_cache_entries, 5u);

  game.Write("config.yaml", "ReducePaletteRange: false\n");
  const TexturePackConfig preferred = LoadGameConfig(game.path(), base);
  EXPECT_FALSE(preferred.dump_texture_pages);
  EXPECT_FALSE(preferred.reduce_palette_range);
}

TEST(TextureReplacementIndex, ABrokenConfigLeavesTheGlobalSettings)
{
  TempGameFolder game;
  game.Write("config.yaml", "DumpTexturePages: maybe\n");
  TexturePackConfig base;
  base.dump_texture_pages = false;
  EXPECT_EQ(LoadGameConfig(game.path(), base), base);
}

// Packs read through content:// (Android's data folder), dumps written locally.

#ifndef _WIN32

#include "core/settings.h"

#include "fake_content_provider.h"

namespace {

class ContentPack : public ::testing::Test
{
protected:
  void SetUp() override
  {
    m_provider = std::make_unique<FakeProvider>(m_tree.path());
    FileSystem::SetContentProvider(m_provider.get());
    m_old_textures = EmuFolders::Textures;
    EmuFolders::Textures = Path::Combine(m_local.path(), "textures");
  }

  void TearDown() override
  {
    TextureReplacements::SetPackFolder(std::string());
    TextureReplacements::Shutdown();
    EmuFolders::Textures = m_old_textures;
    FileSystem::SetContentProvider(nullptr);
  }

  static std::string PackGame() { return fmt::format("{}/textures/SLUS-00000", FakeProvider::ROOT); }

  TempGameFolder m_tree;  // served as FakeProvider::ROOT
  TempGameFolder m_local; // the app's own storage
  std::unique_ptr<FakeProvider> m_provider;
  std::string m_old_textures;
};

} // namespace

TEST_F(ContentPack, APackIsIndexedThroughContent)
{
  m_tree.Write(fmt::format("textures/SLUS-00000/replacements/{}.png", UPLOAD_NAME));
  m_tree.Write(fmt::format("textures/SLUS-00000/dumps/{}.png", UPLOAD_NAME_2));
  m_tree.Write(fmt::format("textures/SLUS-00000/{}.png", VRAM_WRITE_NAME));
  m_tree.Write("textures/SLUS-00000/config.yaml", "DumpTexturePages: true\n");

  const TexturePackConfig config = LoadGameConfig(PackGame(), Path::Combine(EmuFolders::Textures, "SLUS-00000"),
                                                  TexturePackConfig());
  EXPECT_TRUE(config.dump_texture_pages);

  ReplacementIndex index;
  index.Build(PackGame(), config);
  ASSERT_EQ(index.GetTextureCount(), 1u);
  EXPECT_TRUE(index.GetTextures()[0].path.starts_with(FakeProvider::ROOT));
  EXPECT_EQ(index.GetVRAMWriteCount(), 1u);
}

TEST_F(ContentPack, IndexingAContentTreeListsEachFolderOnce)
{
  for (u32 i = 0; i < 50; i++)
  {
    m_tree.Write(fmt::format("textures/SLUS-00000/replacements/a/texupload-P4-{:016X}-D7ADDCD353C6521A-64x256-0-0-16x16-P0-15.png", i));
    m_tree.Write(fmt::format("textures/SLUS-00000/replacements/b/texupload-P4-{:016X}-D7ADDCD353C6521A-64x256-0-0-16x16-P0-15.png", i + 1000));
  }

  ReplacementIndex index;
  index.Build(PackGame(), TexturePackConfig());
  EXPECT_EQ(index.GetTextureCount(), 100u);
  EXPECT_EQ(m_provider->GetListCalls(), 4u); // the game folder, replacements/, a/ and b/
  EXPECT_EQ(m_provider->GetStatCalls(), 0u);
}

TEST_F(ContentPack, AnUnreadablePackFolderIndexesNothing)
{
  m_tree.Write(fmt::format("textures/SLUS-00000/replacements/{}.png", UPLOAD_NAME));
  m_tree.Write("textures/SLUS-00000/config.yaml", "DumpTexturePages: true\n");
  m_provider->SetRefuseAll(true);

  ReplacementIndex index;
  index.Build(PackGame(), TexturePackConfig());
  EXPECT_EQ(index.GetTextureCount(), 0u);
  EXPECT_EQ(LoadGameConfig(PackGame(), Path::Combine(EmuFolders::Textures, "SLUS-00000"), TexturePackConfig()),
            TexturePackConfig());
}

TEST_F(ContentPack, ALocalConfigIsReadOnlyWithoutThePacksOwn)
{
  const std::string local_game = Path::Combine(EmuFolders::Textures, "SLUS-00000");
  FileSystem::EnsureDirectoryExists(local_game.c_str(), true);
  FileSystem::WriteStringToFile(Path::Combine(local_game, "config.yaml").c_str(), "DumpC16Textures: true\n");

  EXPECT_TRUE(LoadGameConfig(PackGame(), local_game, TexturePackConfig()).dump_c16_textures);

  m_tree.Write("textures/SLUS-00000/config.yml", "ReducePaletteRange: false\n");
  const TexturePackConfig config = LoadGameConfig(PackGame(), local_game, TexturePackConfig());
  EXPECT_FALSE(config.dump_c16_textures);
  EXPECT_FALSE(config.reduce_palette_range);
  EXPECT_TRUE(HasGameConfig(PackGame()));
  EXPECT_FALSE(HasGameConfig(Path::Combine(EmuFolders::Textures, "SLUS-99999")));
}

TEST_F(ContentPack, DumpsGoToTheLocalFolder)
{
  SetPackFolder(fmt::format("{}/textures", FakeProvider::ROOT));
  SetGameID("SLUS-00000");
  EXPECT_EQ(GetGameDirectory(), PackGame());
  EXPECT_EQ(GetLocalGameDirectory(), Path::Combine(EmuFolders::Textures, "SLUS-00000"));
  EXPECT_EQ(GetTextureDumpDirectory(), Path::Combine(EmuFolders::Textures, "SLUS-00000/dumps"));

  // Without a pack folder, both are the local one, as on desktop.
  SetPackFolder(std::string());
  EXPECT_EQ(GetGameDirectory(), GetLocalGameDirectory());
}

#endif
