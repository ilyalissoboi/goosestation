// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/achievements_offline.h"
#include "core/achievements_offline_store.h"

#include "util/http_downloader.h"

#include "common/file_system.h"
#include "common/path.h"

#include <gtest/gtest.h>

#include <cstdlib>
#include <map>
#include <optional>
#include <string>
#include <vector>

using AchievementsOffline::Record;
using AchievementsOffline::Store;

namespace {

constexpr const char* HASH = "0123abcd0123abcd0123abcd0123abcd";
constexpr const char* TOKEN = "fake-token-1";
constexpr s64 T0 = 1700000000;

constexpr const char* ACCEPTED =
  R"({"Success":true,"Score":10,"SoftcoreScore":20,"AchievementID":101,"AchievementsRemaining":3})";
constexpr const char* ALREADY_UNLOCKED =
  R"({"Success":true,"Error":"User already has this achievement unlocked.","Score":10,"SoftcoreScore":20,)"
  R"("AchievementID":101,"AchievementsRemaining":3})";
constexpr const char* REFUSED = R"({"Success":false,"Error":"Achievement not found","Code":"not_found"})";
constexpr const char* ENTRY_ACCEPTED =
  R"({"Success":true,"Response":{"Score":4321,"BestScore":4321,"RankInfo":{"Rank":1,"NumEntries":"1"},)"
  R"("TopEntries":[],"LBData":{"Format":"VALUE","LeaderboardID":201,"GameID":3001,"Title":"Fastest lap",)"
  R"("LowerIsBetter":0}}})";

// A fresh directory under $TMPDIR (or /tmp), deleted with its contents when the test ends.
class TempDirectory
{
public:
  TempDirectory()
  {
    const char* tmpdir = std::getenv("TMPDIR");
    std::string templ = Path::Combine((tmpdir && *tmpdir) ? tmpdir : "/tmp", "common-tests-queue-XXXXXX");
    if (mkdtemp(templ.data()))
      m_path = std::move(templ);
  }

  ~TempDirectory()
  {
    if (!m_path.empty())
      FileSystem::RecursiveDeleteDirectory(m_path.c_str());
  }

  const std::string& GetPath() const { return m_path; }

private:
  std::string m_path;
};

// Requests go nowhere: each stays started until the test answers it.
class FakeHTTPDownloader final : public HTTPDownloader
{
public:
  /// Each request posted so far, as its form fields.
  std::vector<std::map<std::string, std::string>> Posted() const
  {
    std::vector<std::map<std::string, std::string>> forms;
    for (const std::string& data : m_post_data)
      forms.push_back(AchievementsOffline::ParseForm(data));
    return forms;
  }

  /// Completes request index (in the order posted) with status and body.
  void Answer(size_t index, s32 status, std::string_view body)
  {
    Request* request = m_requests.at(index);
    ASSERT_NE(request, nullptr);
    m_requests[index] = nullptr;
    request->status_code = status;
    request->data.assign(body.begin(), body.end());
    request->state.store(Request::State::Complete);
    PollRequests();
  }

protected:
  Request* InternalCreateRequest() override { return new Request(); }
  void InternalPollRequests() override {}
  bool StartRequest(Request* request) override
  {
    request->state.store(Request::State::Started);
    m_requests.push_back(request);
    m_post_data.push_back(request->post_data);
    return true;
  }
  void CloseRequest(Request* request) override { delete request; }

private:
  std::vector<Request*> m_requests;
  std::vector<std::string> m_post_data;
};

std::map<std::string, std::string> UnlockForm(std::string user = "goose", u32 id = 101)
{
  return {{"r", "awardachievement"}, {"u", std::move(user)}, {"t", TOKEN},
          {"a", std::to_string(id)}, {"h", "0"},             {"m", HASH}};
}

std::map<std::string, std::string> EntryForm()
{
  return {{"r", "submitlbentry"}, {"u", "goose"}, {"t", TOKEN}, {"i", "201"}, {"s", "4321"}, {"m", HASH}};
}

Record EarlierUnlock(std::string user = "goose", u32 id = 101)
{
  return Record{Record::Kind::Unlock, std::move(user), HASH, id, false, 0, T0, "Ace Pilot", false};
}

Record EarlierEntry()
{
  return Record{Record::Kind::Entry, "goose", HASH, 201, true, 4321, T0, "Fastest lap", false};
}

class AchievementsOfflineQueue : public ::testing::Test
{
protected:
  /// Records from an earlier run, on disk before the queue is turned on.
  void Keep(const std::vector<Record>& records)
  {
    Store store(m_tmp.GetPath());
    for (const Record& record : records)
      ASSERT_TRUE(store.Add(record));
  }

  void Enable(bool count_online_retries = true)
  {
    AchievementsOffline::Enable(m_tmp.GetPath(),
                                AchievementsOffline::Callbacks{
                                  .changed = [this]() { m_changed++; },
                                  .refused = [this](std::string_view title) { m_refused.emplace_back(title); },
                                  .sent = [this](u32 count) { m_sent.push_back(count); },
                                },
                                count_online_retries);
  }

  void TearDown() override { AchievementsOffline::EndSession(); }

  size_t Waiting() const
  {
    u32 total = 0;
    for (const auto& [user, count] : AchievementsOffline::CountsByUser())
      total += count;
    return total;
  }

  TempDirectory m_tmp;
  FakeHTTPDownloader m_http;
  u32 m_changed = 0;
  std::vector<std::string> m_refused;
  std::vector<u32> m_sent;
};

} // namespace

TEST_F(AchievementsOfflineQueue, AnUnlockIsKeptBeforeItGoes)
{
  Enable();
  AchievementsOffline::OnRequest(UnlockForm(), "Ace Pilot", T0);
  EXPECT_EQ(Waiting(), 1u);
  EXPECT_GE(m_changed, 1u);

  const std::optional<std::string> queue =
    FileSystem::ReadFileToString(Path::Combine(m_tmp.GetPath(), "queue.json").c_str());
  ASSERT_TRUE(queue.has_value());
  EXPECT_NE(queue->find("\"id\""), std::string::npos);
  EXPECT_EQ(queue->find(TOKEN), std::string::npos);
}

TEST_F(AchievementsOfflineQueue, ARetryWithOIsNotKeptTwice)
{
  Enable();
  AchievementsOffline::OnRequest(UnlockForm(), "Ace Pilot", T0);
  auto retry = UnlockForm();
  retry["o"] = "30";
  AchievementsOffline::OnRequest(retry, "Ace Pilot", T0 + 30);
  EXPECT_EQ(Waiting(), 1u);
}

TEST_F(AchievementsOfflineQueue, AnAcceptedUnlockIsCleared)
{
  Enable();
  AchievementsOffline::OnRequest(UnlockForm(), "Ace Pilot", T0);
  AchievementsOffline::OnAnswer(UnlockForm(), 200, ACCEPTED);
  EXPECT_EQ(Waiting(), 0u);
  EXPECT_TRUE(m_refused.empty());
}

TEST_F(AchievementsOfflineQueue, AlreadyUnlockedIsCleared)
{
  Enable();
  AchievementsOffline::OnRequest(UnlockForm(), "Ace Pilot", T0);
  AchievementsOffline::OnAnswer(UnlockForm(), 200, ALREADY_UNLOCKED);
  EXPECT_EQ(Waiting(), 0u);
  EXPECT_TRUE(m_refused.empty());
}

TEST_F(AchievementsOfflineQueue, ANoAnswerKeepsIt)
{
  Enable();
  AchievementsOffline::OnRequest(UnlockForm(), "Ace Pilot", T0);
  AchievementsOffline::OnAnswer(UnlockForm(), HTTPDownloader::HTTP_STATUS_ERROR, "");
  EXPECT_EQ(Waiting(), 1u);
}

TEST_F(AchievementsOfflineQueue, ARefusalIsDroppedAndNamed)
{
  Enable();
  AchievementsOffline::OnRequest(UnlockForm(), "Ace Pilot", T0);
  AchievementsOffline::OnAnswer(UnlockForm(), 404, REFUSED);
  EXPECT_EQ(Waiting(), 0u);
  ASSERT_EQ(m_refused.size(), 1u);
  EXPECT_EQ(m_refused[0], "Ace Pilot");
  EXPECT_TRUE(AchievementsOffline::TakeReportedRefusal(false, 101));
  EXPECT_FALSE(AchievementsOffline::TakeReportedRefusal(false, 101));
}

TEST_F(AchievementsOfflineQueue, AnEntryIsKept)
{
  Enable();
  AchievementsOffline::OnRequest(EntryForm(), "Fastest lap", T0);
  const Store store(m_tmp.GetPath());
  ASSERT_EQ(store.Records().size(), 1u);
  EXPECT_EQ(store.Records()[0].kind, Record::Kind::Entry);
  EXPECT_EQ(store.Records()[0].id, 201u);
  EXPECT_EQ(store.Records()[0].score, 4321);
  EXPECT_TRUE(store.Records()[0].hardcore);
}

TEST_F(AchievementsOfflineQueue, WaitingRecordsAreSentBackdated)
{
  Keep({EarlierUnlock()});
  Enable();
  AchievementsOffline::SendWaiting(m_http, nullptr, "goose", TOKEN, T0 + 600);

  const auto posted = m_http.Posted();
  ASSERT_EQ(posted.size(), 1u);
  EXPECT_EQ(posted[0].at("r"), "awardachievement");
  EXPECT_EQ(posted[0].at("a"), "101");
  EXPECT_EQ(posted[0].at("u"), "goose");
  EXPECT_EQ(posted[0].at("o"), "600");

  m_http.Answer(0, 200, ACCEPTED);
  EXPECT_EQ(Waiting(), 0u);
  EXPECT_EQ(m_sent, std::vector<u32>{1});
}

TEST_F(AchievementsOfflineQueue, OnlyTheUsersOwnAreSent)
{
  Keep({EarlierUnlock("goose"), EarlierUnlock("duck")});
  Enable();
  AchievementsOffline::SendWaiting(m_http, nullptr, "Goose", TOKEN, T0 + 600);

  const auto posted = m_http.Posted();
  ASSERT_EQ(posted.size(), 1u);
  EXPECT_EQ(posted[0].at("u"), "Goose");
  m_http.Answer(0, 200, ACCEPTED);
  ASSERT_EQ(AchievementsOffline::CountsByUser().size(), 1u);
  EXPECT_EQ(AchievementsOffline::CountsByUser()[0].first, "duck");
}

TEST_F(AchievementsOfflineQueue, ARequestOutIsNotSentAgain)
{
  Keep({EarlierUnlock("goose", 101)});
  Enable();
  AchievementsOffline::SendWaiting(m_http, nullptr, "goose", TOKEN, T0 + 600);
  AchievementsOffline::SendWaiting(m_http, nullptr, "goose", TOKEN, T0 + 601);
  EXPECT_EQ(m_http.Posted().size(), 1u);

  // One rcheevos is sending is its own to retry.
  AchievementsOffline::OnRequest(UnlockForm("goose", 102), "Top Gun", T0 + 602);
  AchievementsOffline::SendWaiting(m_http, nullptr, "goose", TOKEN, T0 + 603);
  EXPECT_EQ(m_http.Posted().size(), 1u);
}

TEST_F(AchievementsOfflineQueue, EntriesCountAsUnlocks)
{
  Keep({EarlierUnlock(), EarlierEntry()});
  Enable();
  AchievementsOffline::SendWaiting(m_http, nullptr, "goose", TOKEN, T0 + 600);
  ASSERT_EQ(m_http.Posted().size(), 2u);

  const size_t entry = (m_http.Posted()[0].at("r") == "submitlbentry") ? 0 : 1;
  m_http.Answer(1 - entry, 200, ACCEPTED);
  EXPECT_TRUE(m_sent.empty());
  m_http.Answer(entry, 200, ENTRY_ACCEPTED);
  EXPECT_EQ(m_sent, std::vector<u32>{2});
}

// Review Focus 2: on desktop, an unlock rcheevos got through itself in a game started online is the Reconnected
// toast's, not counted again.
TEST_F(AchievementsOfflineQueue, OnlineRetriesAreNotCountedOnDesktop)
{
  Enable(false);
  AchievementsOffline::OnRequest(UnlockForm(), "Ace Pilot", T0);
  AchievementsOffline::OnAnswer(UnlockForm(), HTTPDownloader::HTTP_STATUS_ERROR, "");
  AchievementsOffline::OnAnswer(UnlockForm(), 200, ACCEPTED);
  EXPECT_EQ(Waiting(), 0u);
  EXPECT_TRUE(m_sent.empty());
}

TEST_F(AchievementsOfflineQueue, AnEarlierRunsRecordIsCounted)
{
  Keep({EarlierUnlock()});
  Enable(false);
  AchievementsOffline::SendWaiting(m_http, nullptr, "goose", TOKEN, T0 + 600);
  m_http.Answer(0, 200, ACCEPTED);
  EXPECT_EQ(m_sent, std::vector<u32>{1});
}

TEST_F(AchievementsOfflineQueue, AnOfflineSessionsRecordIsCounted)
{
  Enable(false);
  AchievementsOffline::BeginSession(true);
  AchievementsOffline::OnRequest(UnlockForm(), "Ace Pilot", T0);
  AchievementsOffline::OnAnswer(UnlockForm(), HTTPDownloader::HTTP_STATUS_ERROR, "");
  AchievementsOffline::OnAnswer(UnlockForm(), 200, ACCEPTED);
  EXPECT_EQ(m_sent, std::vector<u32>{1});
}

TEST_F(AchievementsOfflineQueue, AndroidCountsOnlineRetries)
{
  Enable();
  AchievementsOffline::OnRequest(UnlockForm(), "Ace Pilot", T0);
  AchievementsOffline::OnAnswer(UnlockForm(), HTTPDownloader::HTTP_STATUS_ERROR, "");
  AchievementsOffline::OnAnswer(UnlockForm(), 200, ACCEPTED);
  EXPECT_EQ(m_sent, std::vector<u32>{1});
}

TEST_F(AchievementsOfflineQueue, EnableStartsClean)
{
  Enable();
  AchievementsOffline::BeginSession(true);
  AchievementsOffline::OnRequest(UnlockForm(), "Ace Pilot", T0);
  Enable();
  EXPECT_FALSE(AchievementsOffline::InSession());

  // The request out before isn't remembered: the record goes again.
  AchievementsOffline::SendWaiting(m_http, nullptr, "goose", TOKEN, T0 + 600);
  EXPECT_EQ(m_http.Posted().size(), 1u);
}

TEST_F(AchievementsOfflineQueue, CountsAreByUserAndGame)
{
  Keep({EarlierUnlock("goose", 101), EarlierUnlock("goose", 102), EarlierUnlock("duck", 101)});
  Enable();
  const auto counts = AchievementsOffline::CountsByUser();
  ASSERT_EQ(counts.size(), 2u);
  EXPECT_EQ(counts[0], (std::pair<std::string, u32>("goose", 2)));
  EXPECT_EQ(counts[1], (std::pair<std::string, u32>("duck", 1)));
  EXPECT_EQ(AchievementsOffline::CountForGame("GOOSE", HASH), 2u);
  EXPECT_EQ(AchievementsOffline::CountForGame("goose", "ffffffffffffffffffffffffffffffff"), 0u);
}

// A game started offline said its unlocks are sent later: rcheevos's failed sends there aren't a lost connection.
TEST_F(AchievementsOfflineQueue, ConnectionToastsAreForGamesStartedOnline)
{
  Enable(false);
  EXPECT_TRUE(AchievementsOffline::ConnectionToastsApply());
  AchievementsOffline::BeginSession(false);
  EXPECT_FALSE(AchievementsOffline::ConnectionToastsApply());
  AchievementsOffline::EndSession();
  EXPECT_TRUE(AchievementsOffline::ConnectionToastsApply());
}

namespace {

std::map<std::string, std::string> LoginForm()
{
  return {{"r", "login2"}, {"u", "goose"}, {"t", TOKEN}};
}

// What a game's online start saved: the account, the hash's game and its set.
void SaveForOfflineStart(const std::string& folder)
{
  Store store(folder);
  ASSERT_TRUE(store.SaveUser(AchievementsOffline::UserInfo{"goose", "Goose", "", 300, 940}));
  ASSERT_TRUE(store.SaveHash(HASH, 3001));
  ASSERT_TRUE(store.SaveSet(3001, R"({"Success":true,"GameId":3001})"));
}

} // namespace

// Review Focus 5: a client restarted mid-game (Encore, Spectator, Unofficial) signs in again from what was saved,
// and the game stays started offline.
TEST_F(AchievementsOfflineQueue, ARestartKeepsTheSessionAndSignsInAgain)
{
  SaveForOfflineStart(m_tmp.GetPath());
  Enable(false);
  AchievementsOffline::BeginSession(true);

  AchievementsOffline::RestartSession();
  EXPECT_TRUE(AchievementsOffline::InSession());
  EXPECT_FALSE(AchievementsOffline::SignInIsBuilt());

  EXPECT_TRUE(AchievementsOffline::AnswerOffline(LoginForm(), HASH, T0).has_value());
  EXPECT_TRUE(AchievementsOffline::InSession());
  EXPECT_TRUE(AchievementsOffline::SignInIsBuilt());

  // Once: the session's sign-in is made.
  EXPECT_FALSE(AchievementsOffline::AnswerOffline(LoginForm(), HASH, T0).has_value());
}

// The network came back before the restart: its real sign-in isn't built, and the game still started offline.
TEST_F(AchievementsOfflineQueue, ARealSignInAfterARestartKeepsTheSession)
{
  SaveForOfflineStart(m_tmp.GetPath());
  Enable(false);
  AchievementsOffline::BeginSession(true);

  AchievementsOffline::RestartSession();
  AchievementsOffline::OnAnswer(LoginForm(), 200, R"({"Success":true,"User":"goose","Token":"x"})");
  EXPECT_TRUE(AchievementsOffline::InSession());
  EXPECT_FALSE(AchievementsOffline::SignInIsBuilt());
  EXPECT_FALSE(AchievementsOffline::AnswerOffline(LoginForm(), HASH, T0).has_value());
}

TEST_F(AchievementsOfflineQueue, ARestartOutsideASessionDoesNothing)
{
  Enable(false);
  AchievementsOffline::RestartSession();
  EXPECT_FALSE(AchievementsOffline::InSession());
}
