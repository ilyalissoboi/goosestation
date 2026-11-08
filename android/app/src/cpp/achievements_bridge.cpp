// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "achievements_bridge.h"
#include "android_host.h"
#include "emu_thread.h"
#include "game_codes.h"

#include "core/achievements_offline.h"
#include "core/achievements_private.h"
#include "core/cheats.h"
#include "core/host.h"
#include "core/settings.h"
#include "core/system.h"

#include "common/error.h"
#include "common/log.h"
#include "common/string_util.h"

#include "rc_client.h"

#include <atomic>
#include <charconv>
#include <ctime>

Log_SetChannel(AndroidHost);

AchievementsBridge::Account AchievementsBridge::GetAccount()
{
  Account account = {Achievements::AndroidSignIn::SignedOut, {}, {}, 0, 0};
  EmuThread::RunOnThread(
    [&account]() {
      account.state = Achievements::GetAndroidSignIn();
      account.username = Host::GetBaseStringSettingValue("Cheevos", "Username");
      if (account.state != Achievements::AndroidSignIn::SignedIn)
        return;

      const auto lock = Achievements::GetLock();
      if (const rc_client_user_t* user = rc_client_get_user_info(Achievements::GetClient()))
      {
        account.display_name = user->display_name ? user->display_name : "";
        account.score = user->score;
        account.softcore_score = user->score_softcore;
      }
    },
    true);
  return account;
}

std::optional<std::string> AchievementsBridge::SignIn(std::string username, std::string password)
{
  std::optional<std::string> result;
  EmuThread::RunOnThread(
    [&result, &username, &password]() {
      // Achievements on first, so the sign-in is the running client's and the game's set loads with it.
      const bool was_enabled = Host::GetBaseBoolSettingValue("Cheevos", "Enabled", false);
      if (!was_enabled)
      {
        Host::SetBaseBoolSettingValue("Cheevos", "Enabled", true);
        Host::CommitBaseSettingChanges();
        System::ApplySettings(false);
      }

      Error error;
      if (Achievements::Login(username.c_str(), password.c_str(), &error))
        return;

      result = error.GetDescription();
      if (!was_enabled)
      {
        Host::SetBaseBoolSettingValue("Cheevos", "Enabled", false);
        Host::CommitBaseSettingChanges();
        System::ApplySettings(false);
      }
    },
    true);

  // The password goes no further than the core's request.
  std::fill(password.begin(), password.end(), '\0');
  return result;
}

void AchievementsBridge::SignOut()
{
  EmuThread::RunOnThread(
    []() {
      Achievements::Logout();
      Host::SetBaseBoolSettingValue("Cheevos", "Enabled", false);
      Host::CommitBaseSettingChanges();
      System::ApplySettings(false);
    },
    true);
}

AchievementsBridge::Queue AchievementsBridge::GetQueue()
{
  Queue queue = {0, {}};
  EmuThread::RunOnThread(
    [&queue]() {
      const std::string username = Host::GetBaseStringSettingValue("Cheevos", "Username");
      const auto lock = Achievements::GetLock();
      for (auto& [user, count] : AchievementsOffline::CountsByUser())
      {
        if (!username.empty() && StringUtil::EqualNoCase(user, username))
          queue.own += count;
        else
          queue.others.emplace_back(std::move(user), count);
      }
    },
    true);
  return queue;
}

AchievementsBridge::GameSummary AchievementsBridge::GetGameSummary()
{
  GameSummary summary = {Achievements::AndroidGameStatus::Off, 0, 0, 0, 0, false, false, false, false, 0};
  EmuThread::RunOnThread(
    [&summary]() {
      summary.status = Achievements::GetAndroidGameStatus();
      summary.hardcore = Achievements::IsHardcoreModeActive();
      const auto lock = Achievements::GetLock();
      summary.offline = AchievementsOffline::InSession();
      if (const rc_client_user_t* user = rc_client_get_user_info(Achievements::GetClient()); user && user->username)
      {
        const rc_client_game_t* game = rc_client_get_game_info(Achievements::GetClient());
        if (game && game->hash)
          summary.waiting = AchievementsOffline::CountForGame(user->username, game->hash);
      }
      if (!Achievements::HasActiveGame())
        return;

      const rc_client_user_game_summary_t& counts = Achievements::GetGameSummary();
      summary.unlocked = counts.num_unlocked_achievements;
      summary.total = counts.num_core_achievements;
      summary.points = counts.points_unlocked;
      summary.total_points = counts.points_core;
      summary.has_achievements = Achievements::HasAchievements();
      summary.has_leaderboards = Achievements::HasLeaderboards();
    },
    true);
  return summary;
}

std::vector<AchievementsBridge::AchievementSection> AchievementsBridge::GetAchievementSections()
{
  std::vector<AchievementSection> sections;
  EmuThread::RunOnThread(
    [&sections]() {
      const auto lock = Achievements::GetLock();
      rc_client_t* client = Achievements::GetClient();
      if (!client || !Achievements::HasActiveGame())
        return;

      rc_client_achievement_list_t* list = rc_client_create_achievement_list(
        client, RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE_AND_UNOFFICIAL, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
      if (!list)
        return;

      for (u32 i = 0; i < list->num_buckets; i++)
      {
        const rc_client_achievement_bucket_t& bucket = list->buckets[i];
        AchievementSection& section = sections.emplace_back();
        section.bucket = static_cast<u8>(bucket.bucket_type);
        section.label = bucket.label ? bucket.label : "";
        for (u32 j = 0; j < bucket.num_achievements; j++)
        {
          const rc_client_achievement_t* cheevo = bucket.achievements[j];
          const bool unlocked = (cheevo->state == RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED);
          section.items.push_back(AchievementItem{
            cheevo->id, cheevo->title ? cheevo->title : "", cheevo->description ? cheevo->description : "",
            Achievements::GetAchievementBadgePath(cheevo, cheevo->state), cheevo->points,
            unlocked ? static_cast<s64>(cheevo->unlock_time) : 0, cheevo->measured_progress, cheevo->measured_percent});
        }
      }

      rc_client_destroy_achievement_list(list);
    },
    true);
  return sections;
}

std::vector<AchievementsBridge::LeaderboardItem> AchievementsBridge::GetLeaderboards()
{
  std::vector<LeaderboardItem> boards;
  EmuThread::RunOnThread(
    [&boards]() {
      const auto lock = Achievements::GetLock();
      rc_client_t* client = Achievements::GetClient();
      if (!client || !Achievements::HasLeaderboards())
        return;

      rc_client_leaderboard_list_t* list =
        rc_client_create_leaderboard_list(client, RC_CLIENT_LEADERBOARD_LIST_GROUPING_NONE);
      if (!list)
        return;

      for (u32 i = 0; i < list->num_buckets; i++)
      {
        const rc_client_leaderboard_bucket_t& bucket = list->buckets[i];
        if (bucket.bucket_type == RC_CLIENT_LEADERBOARD_BUCKET_UNSUPPORTED)
          continue;
        for (u32 j = 0; j < bucket.num_leaderboards; j++)
        {
          const rc_client_leaderboard_t* board = bucket.leaderboards[j];
          boards.push_back({board->id, board->title ? board->title : "", board->description ? board->description : ""});
        }
      }

      rc_client_destroy_leaderboard_list(list);
    },
    true);
  return boards;
}

namespace {
/// A leaderboard's two fetches, answered together once both are back. CPU thread.
struct LeaderboardFetch
{
  s32 request;
  u32 pending = 2;
  AchievementsBridge::LeaderboardPage page = {0, {}, {}, -1};
  bool top_ok = false;
  std::string error;
};

std::string FetchError(int result, const char* error_message)
{
  if (result == RC_NO_RESPONSE || !error_message || !*error_message)
    return "Couldn't reach RetroAchievements";
  return error_message;
}

void CopyEntries(const rc_client_leaderboard_entry_list_t* list, std::vector<AchievementsBridge::LeaderboardEntry>* to)
{
  for (u32 i = 0; i < list->num_entries; i++)
  {
    const rc_client_leaderboard_entry_t& entry = list->entries[i];
    to->push_back({entry.rank, entry.user ? entry.user : "", entry.display});
  }
}

void FinishFetch(LeaderboardFetch* fetch)
{
  if (--fetch->pending > 0)
    return;

  if (fetch->top_ok)
    AndroidHost::NotifyLeaderboard(fetch->request, &fetch->page, {});
  else
    AndroidHost::NotifyLeaderboard(fetch->request, nullptr, fetch->error);
  delete fetch;
}

void TopFetched(int result, const char* error_message, rc_client_leaderboard_entry_list_t* list, rc_client_t*,
                void* userdata)
{
  LeaderboardFetch* fetch = static_cast<LeaderboardFetch*>(userdata);
  if (result == RC_OK && list)
  {
    fetch->top_ok = true;
    fetch->page.total = list->total_entries;
    CopyEntries(list, &fetch->page.top);
  }
  else
  {
    fetch->error = FetchError(result, error_message);
  }
  if (list)
    rc_client_destroy_leaderboard_entry_list(list);
  FinishFetch(fetch);
}

void AroundFetched(int result, const char* error_message, rc_client_leaderboard_entry_list_t* list, rc_client_t*,
                   void* userdata)
{
  // No entry of the player's isn't an error: the page just has no "around you".
  LeaderboardFetch* fetch = static_cast<LeaderboardFetch*>(userdata);
  if (result == RC_OK && list)
  {
    CopyEntries(list, &fetch->page.around);
    fetch->page.you_index = list->user_index;
  }
  if (list)
    rc_client_destroy_leaderboard_entry_list(list);
  FinishFetch(fetch);
}
} // namespace

void AchievementsBridge::FetchLeaderboard(u32 id, s32 request)
{
  EmuThread::RunOnThread(
    [id, request]() {
      const auto lock = Achievements::GetLock();
      rc_client_t* client = Achievements::GetClient();
      if (!client || !Achievements::HasLeaderboards())
      {
        AndroidHost::NotifyLeaderboard(request, nullptr, "This game has no leaderboards.");
        return;
      }

      // Each callback comes exactly once, even for a fetch that can't start (rcheevos calls it before returning).
      LeaderboardFetch* fetch = new LeaderboardFetch{request};
      rc_client_begin_fetch_leaderboard_entries(client, id, 1, 10, TopFetched, fetch);
      rc_client_begin_fetch_leaderboard_entries_around_user(client, id, 9, AroundFetched, fetch);
    },
    false);
}

bool AchievementsBridge::HardcoreWouldStart()
{
  return g_settings.achievements_enabled && g_settings.achievements_hardcore_mode &&
         Achievements::GetAndroidSignIn() != Achievements::AndroidSignIn::SignedOut;
}

bool AchievementsBridge::IsHardcoreActive()
{
  return Achievements::IsHardcoreModeActive();
}

void AchievementsBridge::SetServerForTest(const std::string& host)
{
  Achievements::SetServerForTest(host);
}

namespace {
// How far SkipRetryWaitForTest() has moved rcheevos's clock on, in ms.
std::atomic<rc_clock_t> s_clock_skip_for_test{0};

// rcheevos's own clock (CLOCK_MONOTONIC, in ms), moved on by s_clock_skip_for_test.
rc_clock_t ClockForTest(const rc_client_t*)
{
  timespec now;
  if (clock_gettime(CLOCK_MONOTONIC, &now) < 0)
    return 0;
  return static_cast<rc_clock_t>(now.tv_sec) * 1000 + static_cast<rc_clock_t>(now.tv_nsec) / 1000000 +
         s_clock_skip_for_test.load();
}
} // namespace

void AchievementsBridge::SkipRetryWaitForTest()
{
  const auto lock = Achievements::GetLock();
  rc_client_t* client = Achievements::GetClient();
  if (!client)
    return;

  // rcheevos's longest wait between retries is two minutes: what's scheduled comes due at its next idle update.
  s_clock_skip_for_test += 120 * 1000;
  rc_client_set_get_time_millisecs_function(client, ClockForTest);
}

// The core's achievement events, which Big Picture draws on the other platforms.

void Host::OnAchievementsLoginRequested(Achievements::LoginRequestReason reason)
{
  // The core only asks after the server rejected the stored token: it's no good, so it goes. The app says so
  // (OnAchievementsLoginExpired()).
  if (reason == Achievements::LoginRequestReason::TokenInvalid)
    Host::SetSecretSettingValue("Cheevos", "Token", "");
}

void Host::OnAchievementsLoginSuccess(const char* username, u32 points, u32 sc_points, u32 unread_messages)
{
  AndroidHost::NotifyAchievementsChanged();
}

void Host::OnAchievementsRefreshed()
{
  AndroidHost::NotifyAchievementsChanged();
}

void Host::OnAchievementsHardcoreModeChanged(bool enabled)
{
  // No patch code applies in hardcore mode, nor an undo of a load; the player's switches come back with it off.
  if (enabled)
  {
    if (!System::IsShutdown())
    {
      System::SetCheatList(nullptr);
      // Undo load is a load, which hardcore mode allows none of: the state from before is dropped.
      System::ClearUndoLoadState();
    }
  }
  else
  {
    GameCodes::Apply();
  }

  AndroidHost::NotifyAchievementsChanged();
}

void Host::OnAchievementsLoginExpired()
{
  AndroidHost::NotifyAchievementsLoginExpired();
}

void Host::OnAchievementNotification(std::string_view key, float seconds, std::string_view title, std::string_view text,
                                     std::string_view image_path)
{
  // An unlock's notification doesn't carry its points: the client has them.
  s32 points = -1;
  static constexpr std::string_view UNLOCK_PREFIX = "achievement_unlock_";
  if (key.starts_with(UNLOCK_PREFIX))
  {
    u32 id = 0;
    const std::string_view number = key.substr(UNLOCK_PREFIX.size());
    if (std::from_chars(number.data(), number.data() + number.size(), id).ec == std::errc())
    {
      const auto lock = Achievements::GetLock();
      if (const rc_client_achievement_t* cheevo = rc_client_get_achievement_info(Achievements::GetClient(), id))
        points = static_cast<s32>(cheevo->points);
    }
  }

  // An unlock without a connection is sent later, and its card says so.
  const bool offline = key.starts_with(UNLOCK_PREFIX) && Achievements::IsAndroidOffline();
  AndroidHost::NotifyAchievementNotification(key, title, text, image_path, seconds, points, offline);
}

void Host::OnAchievementToast(std::string_view title, std::string_view message, float seconds)
{
  AndroidHost::NotifyAchievementToast(title, message, seconds);
}

void Host::OnAchievementIndicator(AchievementIndicator kind, u32 id, bool shown, std::string_view text,
                                  std::string_view badge_path)
{
  AndroidHost::NotifyAchievementIndicator(static_cast<s32>(kind), id, shown, text, badge_path);
}

void Host::OnAchievementImageDownloaded(std::string_view path)
{
  AndroidHost::NotifyAchievementImage(path);
}
