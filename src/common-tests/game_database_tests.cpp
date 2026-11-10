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
