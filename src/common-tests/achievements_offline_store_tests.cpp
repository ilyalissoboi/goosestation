// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/achievements_offline_store.h"

#include "common/file_system.h"
#include "common/path.h"

#include <gtest/gtest.h>

#include <cstdlib>
#include <string>

using AchievementsOffline::Outcome;
using AchievementsOffline::Record;
using AchievementsOffline::Store;
using AchievementsOffline::UserInfo;

namespace {

// A fresh directory under $TMPDIR (or /tmp), deleted with its contents when the test ends.
class TempDirectory
{
public:
  TempDirectory()
  {
    const char* tmpdir = std::getenv("TMPDIR");
    std::string templ = Path::Combine((tmpdir && *tmpdir) ? tmpdir : "/tmp", "common-tests-offline-XXXXXX");
    if (mkdtemp(templ.data()))
      m_path = std::move(templ);
  }

  ~TempDirectory()
  {
    if (!m_path.empty())
      EXPECT_TRUE(FileSystem::RecursiveDeleteDirectory(m_path.c_str()));
  }

  const std::string& GetPath() const { return m_path; }
  std::string PathFor(std::string_view name) const { return Path::Combine(m_path, name); }

private:
  std::string m_path;
};

constexpr const char* HASH = "0123abcd0123abcd0123abcd0123abcd";
constexpr const char* TOKEN = "fake-token-1";

Record Unlock(std::string user = "goose", u32 id = 101, bool hardcore = false)
{
  return Record{Record::Kind::Unlock, std::move(user), HASH, id, hardcore, 0, 1700000000, "Ace Pilot", false};
}

Record Entry(s64 time, s32 score = 4321)
{
  return Record{Record::Kind::Entry, "goose", HASH, 201, true, score, time, "Fastest lap", false};
}

UserInfo Goose()
{
  return UserInfo{"goose", "Goose", "https://media.example/goose.png", 300, 940};
}

} // namespace

TEST(AchievementsOfflineStore, RecordSurvivesReload)
{
  const TempDirectory tmp;
  {
    Store store(tmp.GetPath());
    ASSERT_TRUE(store.Add(Unlock()));
  }

  const Store store(tmp.GetPath());
  ASSERT_EQ(store.Records().size(), 1u);
  const Record& record = store.Records()[0];
  EXPECT_EQ(record.kind, Record::Kind::Unlock);
  EXPECT_EQ(record.user, "goose");
  EXPECT_EQ(record.game_hash, HASH);
  EXPECT_EQ(record.id, 101u);
  EXPECT_FALSE(record.hardcore);
  EXPECT_EQ(record.time, 1700000000);
  EXPECT_EQ(record.title, "Ace Pilot");
  EXPECT_TRUE(record.waited);
}

TEST(AchievementsOfflineStore, SameRecordIsAddedOnce)
{
  const TempDirectory tmp;
  Store store(tmp.GetPath());
  ASSERT_TRUE(store.Add(Unlock()));
  ASSERT_TRUE(store.Add(Unlock()));
  ASSERT_TRUE(store.Add(Unlock("GOOSE")));
  EXPECT_EQ(store.Records().size(), 1u);

  // Hardcore and casual unlocks of one achievement are two.
  ASSERT_TRUE(store.Add(Unlock("goose", 101, true)));
  EXPECT_EQ(store.Records().size(), 2u);
}

TEST(AchievementsOfflineStore, EntriesWithDifferentTimesAreTwo)
{
  const TempDirectory tmp;
  Store store(tmp.GetPath());
  ASSERT_TRUE(store.Add(Entry(1700000000)));
  ASSERT_TRUE(store.Add(Entry(1700000000)));
  ASSERT_TRUE(store.Add(Entry(1700000100)));
  EXPECT_EQ(store.Records().size(), 2u);
}

TEST(AchievementsOfflineStore, RemoveClearsTheFile)
{
  const TempDirectory tmp;
  {
    Store store(tmp.GetPath());
    ASSERT_TRUE(store.Add(Unlock()));
    ASSERT_TRUE(store.Remove(Unlock()));
    EXPECT_TRUE(store.Records().empty());
  }

  const Store store(tmp.GetPath());
  EXPECT_TRUE(store.Records().empty());
}

TEST(AchievementsOfflineStore, CountsAreByUser)
{
  const TempDirectory tmp;
  Store store(tmp.GetPath());
  ASSERT_TRUE(store.Add(Unlock("goose", 101)));
  ASSERT_TRUE(store.Add(Unlock("goose", 102)));
  ASSERT_TRUE(store.Add(Unlock("swan", 101)));

  EXPECT_EQ(store.CountFor("goose"), 2u);
  EXPECT_EQ(store.CountFor("Goose"), 2u);
  EXPECT_EQ(store.CountFor("swan"), 1u);
  EXPECT_EQ(store.CountFor("duck"), 0u);

  const auto counts = store.CountsByUser();
  ASSERT_EQ(counts.size(), 2u);
  EXPECT_EQ(counts[0], (std::pair<std::string, u32>("goose", 2)));
  EXPECT_EQ(counts[1], (std::pair<std::string, u32>("swan", 1)));
}

// Review Focus 5: a half-written or damaged queue is set aside, and new records are still kept.
TEST(AchievementsOfflineStore, DamagedQueueIsSetAside)
{
  const TempDirectory tmp;
  const std::string queue = tmp.PathFor("queue.json");
  const std::string damaged = R"({"version":1,"records":[)";
  ASSERT_TRUE(FileSystem::WriteStringToFile(queue.c_str(), damaged));

  {
    Store store(tmp.GetPath());
    EXPECT_TRUE(store.Records().empty());
    EXPECT_EQ(FileSystem::ReadFileToString(tmp.PathFor("queue.json.damaged").c_str()), damaged);
    ASSERT_TRUE(store.Add(Unlock()));
  }

  const Store store(tmp.GetPath());
  EXPECT_EQ(store.Records().size(), 1u);
}

TEST(AchievementsOfflineStore, OtherVersionIsSetAside)
{
  const TempDirectory tmp;
  ASSERT_TRUE(FileSystem::WriteStringToFile(tmp.PathFor("queue.json").c_str(), R"({"version":2,"records":[]})"));

  const Store store(tmp.GetPath());
  EXPECT_TRUE(store.Records().empty());
  EXPECT_TRUE(FileSystem::FileExists(tmp.PathFor("queue.json.damaged").c_str()));
}

TEST(AchievementsOfflineStore, SavedAnswersSurviveReload)
{
  const TempDirectory tmp;
  {
    Store store(tmp.GetPath());
    ASSERT_TRUE(store.SaveUser(Goose()));
    ASSERT_TRUE(store.SaveHash(HASH, 3001));
    ASSERT_TRUE(store.SaveSet(3001, R"({"Success":true,"GameId":3001})"));
    ASSERT_TRUE(store.SaveSession("goose", 3001, R"({"Success":true,"Unlocks":[]})"));
  }

  const Store store(tmp.GetPath());
  const auto user = store.LoadUser("Goose");
  ASSERT_TRUE(user.has_value());
  EXPECT_EQ(user->display_name, "Goose");
  EXPECT_EQ(user->avatar_url, "https://media.example/goose.png");
  EXPECT_EQ(user->score, 300u);
  EXPECT_EQ(user->softcore_score, 940u);
  EXPECT_EQ(store.GameIdFor(HASH), 3001u);
  EXPECT_FALSE(store.GameIdFor("ffff").has_value());
  EXPECT_EQ(store.LoadSet(3001), R"({"Success":true,"GameId":3001})");
  EXPECT_FALSE(store.LoadSet(3002).has_value());
  EXPECT_EQ(store.LoadSession("goose", 3001), R"({"Success":true,"Unlocks":[]})");
  EXPECT_FALSE(store.LoadSession("swan", 3001).has_value());
}

TEST(AchievementsOfflineStore, NoTokenInAnyFile)
{
  const TempDirectory tmp;
  Store store(tmp.GetPath());
  ASSERT_TRUE(store.SaveUser(Goose()));
  ASSERT_TRUE(store.SaveHash(HASH, 3001));
  ASSERT_TRUE(store.SaveSet(3001, R"({"Success":true,"GameId":3001})"));
  ASSERT_TRUE(store.SaveSession("goose", 3001, R"({"Success":true,"Unlocks":[]})"));
  ASSERT_TRUE(store.Add(Unlock()));

  FileSystem::FindResultsArray files;
  ASSERT_TRUE(FileSystem::FindFiles(tmp.GetPath().c_str(), "*",
                                    FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_RECURSIVE, &files));
  EXPECT_GE(files.size(), 5u);
  for (const FILESYSTEM_FIND_DATA& file : files)
  {
    const auto contents = FileSystem::ReadFileToString(file.FileName.c_str());
    ASSERT_TRUE(contents.has_value()) << file.FileName;
    EXPECT_EQ(contents->find(TOKEN), std::string::npos) << file.FileName;
  }

  EXPECT_NE(AchievementsOffline::BuildLoginAnswer(Goose(), TOKEN).find(TOKEN), std::string::npos);
}

TEST(AchievementsOfflineStore, ParseFormDecodes)
{
  const auto form = AchievementsOffline::ParseForm("r=awardachievement&u=goose&a=101&m=x%20y%26z&h=0&empty=");
  EXPECT_EQ(form.at("r"), "awardachievement");
  EXPECT_EQ(form.at("u"), "goose");
  EXPECT_EQ(form.at("a"), "101");
  EXPECT_EQ(form.at("m"), "x y&z");
  EXPECT_EQ(form.at("h"), "0");
  EXPECT_EQ(form.at("empty"), "");
  EXPECT_EQ(form.count("t"), 0u);
}

TEST(AchievementsOfflineStore, AcceptedIsDelivered)
{
  EXPECT_EQ(AchievementsOffline::Classify(
              Record::Kind::Unlock, 200,
              R"({"Success":true,"Score":10,"SoftcoreScore":20,"AchievementID":101,"AchievementsRemaining":3})"),
            Outcome::Delivered);
}

// Review Focus 2: an unlock the server already has is delivered, never a refusal.
TEST(AchievementsOfflineStore, AlreadyUnlockedIsDelivered)
{
  EXPECT_EQ(AchievementsOffline::Classify(Record::Kind::Unlock, 200,
                                          R"({"Success":true,"Error":"User already has this achievement unlocked.",)"
                                          R"("Score":10,"SoftcoreScore":20,"AchievementID":101,)"
                                          R"("AchievementsRemaining":3})"),
            Outcome::Delivered);
}

TEST(AchievementsOfflineStore, NoAnswerIsKept)
{
  EXPECT_EQ(AchievementsOffline::Classify(Record::Kind::Unlock, -1, ""), Outcome::Keep);
  EXPECT_EQ(AchievementsOffline::Classify(Record::Kind::Entry, -1, ""), Outcome::Keep);
}

TEST(AchievementsOfflineStore, ServerErrorIsKept)
{
  EXPECT_EQ(AchievementsOffline::Classify(Record::Kind::Unlock, 503, "<html>Service Unavailable</html>"),
            Outcome::Keep);
  EXPECT_EQ(AchievementsOffline::Classify(Record::Kind::Entry, 503, "<html>Service Unavailable</html>"),
            Outcome::Keep);
}

TEST(AchievementsOfflineStore, RefusalIsRefused)
{
  const char* refusal = R"({"Success":false,"Error":"Achievement not found","Code":"not_found"})";
  EXPECT_EQ(AchievementsOffline::Classify(Record::Kind::Unlock, 404, refusal), Outcome::Refused);
  EXPECT_EQ(AchievementsOffline::Classify(Record::Kind::Entry, 404, refusal), Outcome::Refused);
}

TEST(AchievementsOfflineStore, AcceptedEntryIsDelivered)
{
  EXPECT_EQ(AchievementsOffline::Classify(Record::Kind::Entry, 200,
                                          R"({"Success":true,"Response":{"Score":4321,"BestScore":4321,)"
                                          R"("RankInfo":{"Rank":1,"NumEntries":"1"},"TopEntries":[],)"
                                          R"("LBData":{"Format":"VALUE","LeaderboardID":201,"GameID":3001,)"
                                          R"("Title":"Fastest lap","LowerIsBetter":0}}})"),
            Outcome::Delivered);
}

TEST(AchievementsOfflineStore, LoginAnswerHasTheSavedAccount)
{
  const std::string answer = AchievementsOffline::BuildLoginAnswer(Goose(), TOKEN);
  EXPECT_NE(answer.find(R"("Success":true)"), std::string::npos);
  EXPECT_NE(answer.find(R"("User":"goose")"), std::string::npos);
  EXPECT_NE(answer.find(R"("DisplayName":"Goose")"), std::string::npos);
  EXPECT_NE(answer.find(R"("Score":300)"), std::string::npos);
  EXPECT_NE(answer.find(R"("SoftcoreScore":940)"), std::string::npos);
}

TEST(AchievementsOfflineStore, SessionAnswerAddsWaitingUnlocks)
{
  const std::string answer = AchievementsOffline::BuildSessionAnswer(
    R"({"Success":true,"Unlocks":[{"ID":5,"When":1}],"HardcoreUnlocks":[],"ServerNow":1})", {Unlock()}, 1800000000);
  EXPECT_NE(answer.find(R"({"ID":5,"When":1})"), std::string::npos);
  EXPECT_NE(answer.find(R"({"ID":101,"When":1700000000})"), std::string::npos);
  EXPECT_NE(answer.find(R"("ServerNow":1800000000)"), std::string::npos);
  EXPECT_EQ(answer.find(R"("ServerNow":1,)"), std::string::npos);
}

TEST(AchievementsOfflineStore, SessionAnswerAddsHardcoreUnlocksToBoth)
{
  const std::string answer = AchievementsOffline::BuildSessionAnswer(
    R"({"Success":true,"Unlocks":[],"HardcoreUnlocks":[],"ServerNow":1})", {Unlock("goose", 101, true)}, 5);
  const size_t hardcore = answer.find(R"("HardcoreUnlocks")");
  ASSERT_NE(hardcore, std::string::npos);
  const size_t first = answer.find(R"({"ID":101,"When":1700000000})");
  ASSERT_NE(first, std::string::npos);
  EXPECT_NE(answer.find(R"({"ID":101,"When":1700000000})", first + 1), std::string::npos);
}
