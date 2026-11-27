// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Loads the shipped data/resources/gamedb.yaml and discdb.yaml through GameDatabase, so a change to either file that
// the core can't read (or reads differently from scripts/gamedb/gamedb_check.py) fails here. Skipped where the source
// tree isn't there (the Android device run).

#include "core_host_stubs.h"

#include "core/game_database.h"
#include "core/settings.h"

#include "util/cd_image_hasher.h"

#include "common/file_system.h"
#include "common/path.h"

#include <gtest/gtest.h>

#include <cstdlib>
#include <string>

namespace {

class GameDatabaseShippedFiles : public testing::Test
{
protected:
  void SetUp() override
  {
    if (!FileSystem::DirectoryExists(GOOSESTATION_TEST_RESOURCES_DIR))
      GTEST_SKIP() << "No source tree at " << GOOSESTATION_TEST_RESOURCES_DIR;

    const char* tmpdir = std::getenv("TMPDIR");
    std::string templ = Path::Combine((tmpdir && *tmpdir) ? tmpdir : "/tmp", "common-tests-gamedb-XXXXXX");
    ASSERT_NE(mkdtemp(templ.data()), nullptr);
    m_cache = std::move(templ);

    m_old_cache = EmuFolders::Cache;
    EmuFolders::Cache = m_cache;
    CoreHostStubs::SetResourceDirectory(GOOSESTATION_TEST_RESOURCES_DIR);
    GameDatabase::Unload();
  }

  void TearDown() override
  {
    if (m_cache.empty())
      return;
    GameDatabase::Unload();
    CoreHostStubs::SetResourceDirectory({});
    EmuFolders::Cache = m_old_cache;
    EXPECT_TRUE(FileSystem::RecursiveDeleteDirectory(m_cache.c_str()));
  }

private:
  std::string m_cache;
  std::string m_old_cache;
};

} // namespace

TEST_F(GameDatabaseShippedFiles, FindsEntriesBySerialCodeAndHash)
{
  const GameDatabase::Entry* entry = GameDatabase::GetEntryForSerial("SLPS-01204");
  ASSERT_NE(entry, nullptr);
  EXPECT_EQ(entry->title, "'98 Koushien - Koukou Yakyuu Simulation (Japan)");

  // A disc is looked up by its boot executable's serial, through the entries' codes (gamedb.yaml:160-162).
  const GameDatabase::Entry* by_code = GameDatabase::GetEntryForGameDetails("SLPS-02546", 0);
  ASSERT_NE(by_code, nullptr);
  EXPECT_EQ(by_code->serial, "SLPS-01311");

  // A disc without a serial is looked up by its executable's hash id (gamedb.yaml:3483).
  const GameDatabase::Entry* by_hash = GameDatabase::GetEntryForGameDetails("", 0xD05E80C0A958B7D0ull);
  ASSERT_NE(by_hash, nullptr);
  EXPECT_EQ(by_hash->serial, "SLPS-00208");
}

TEST_F(GameDatabaseShippedFiles, LoadsTrackHashes)
{
  GameDatabase::EnsureTrackHashesMapLoaded();
  const GameDatabase::TrackHashesMap& hashes = GameDatabase::GetTrackHashesMap();

  const std::optional<CDImageHasher::Hash> md5 = CDImageHasher::HashFromString("39a936ea7521157838d4e67b24f62f15");
  ASSERT_TRUE(md5.has_value());
  const auto found = hashes.find(md5.value());
  ASSERT_NE(found, hashes.end());
  EXPECT_EQ(found->second.serial, "SLPS-01204");
  EXPECT_GT(hashes.size(), 40000u);
}

TEST(GameDatabaseCDSpeed, CDSpeedTraitsApply)
{
  GameDatabase::Entry entry;
  entry.traits.set(static_cast<int>(GameDatabase::Trait::DisableCDROMReadSpeedup));
  entry.traits.set(static_cast<int>(GameDatabase::Trait::DisableCDROMSeekSpeedup));
  entry.traits.set(static_cast<int>(GameDatabase::Trait::DisableFastBoot));

  Settings settings;
  settings.cdrom_read_speedup = 0;
  settings.cdrom_seek_speedup = 0;
  settings.bios_patch_fast_boot = true;
  entry.ApplySettings(settings, false);

  EXPECT_EQ(settings.cdrom_read_speedup, 1u);
  EXPECT_EQ(settings.cdrom_seek_speedup, 1u);
  EXPECT_FALSE(settings.bios_patch_fast_boot);

  // Their names parse.
  EXPECT_EQ(std::string_view(GameDatabase::GetTraitName(GameDatabase::Trait::DisableCDROMReadSpeedup)), "DisableCDROMReadSpeedup");
  EXPECT_EQ(std::string_view(GameDatabase::GetTraitName(GameDatabase::Trait::DisableCDROMSeekSpeedup)), "DisableCDROMSeekSpeedup");
  EXPECT_EQ(std::string_view(GameDatabase::GetTraitName(GameDatabase::Trait::DisableFastBoot)), "DisableFastBoot");
}

TEST(GameDatabasePGXP, DisableOn2DPolygonsAppliesToTheGivenSettings)
{
  GameDatabase::Entry entry;
  entry.traits.set(static_cast<int>(GameDatabase::Trait::DisablePGXPOn2DPolygons));

  const bool saved_global = g_settings.gpu_pgxp_disable_2d;
  g_settings.gpu_pgxp_disable_2d = false;

  Settings settings;
  settings.gpu_pgxp_enable = true;
  settings.gpu_pgxp_disable_2d = false;
  entry.ApplySettings(settings, false);

  // The game's settings get the switch; the running settings are left to the caller.
  EXPECT_TRUE(settings.gpu_pgxp_disable_2d);
  EXPECT_FALSE(g_settings.gpu_pgxp_disable_2d);

  g_settings.gpu_pgxp_disable_2d = saved_global;
}

TEST_F(GameDatabaseShippedFiles, ShippedCDSpeedEntries)
{
  // The database's own notes: the PlayStation Picks disc's Rebel Assault II demo "can hang if speed up", and SaGa
  // Frontier (USA) has a "black screen if fast boot is used".
  const GameDatabase::Entry* picks = GameDatabase::GetEntryForSerial("SCUS-94960");
  ASSERT_NE(picks, nullptr);
  EXPECT_TRUE(picks->HasTrait(GameDatabase::Trait::DisableCDROMReadSpeedup));
  EXPECT_TRUE(picks->HasTrait(GameDatabase::Trait::DisableCDROMSeekSpeedup));

  const GameDatabase::Entry* saga = GameDatabase::GetEntryForSerial("SCUS-94230");
  ASSERT_NE(saga, nullptr);
  EXPECT_TRUE(saga->HasTrait(GameDatabase::Trait::DisableFastBoot));
}

TEST_F(GameDatabaseShippedFiles, NoSwitchForBIOSLoopGames)
{
  // Medal of Honor - Underground and Rally Cross 2: their BIOS sound loop is avoided with fast boot on, which none of
  // the switches does, and neither has been tested.
  for (const char* serial : {"SLUS-01270", "SCUS-94247"})
  {
    const GameDatabase::Entry* entry = GameDatabase::GetEntryForSerial(serial);
    ASSERT_NE(entry, nullptr) << serial;
    EXPECT_FALSE(entry->HasTrait(GameDatabase::Trait::DisableCDROMReadSpeedup)) << serial;
    EXPECT_FALSE(entry->HasTrait(GameDatabase::Trait::DisableCDROMSeekSpeedup)) << serial;
    EXPECT_FALSE(entry->HasTrait(GameDatabase::Trait::DisableFastBoot)) << serial;
  }
}
