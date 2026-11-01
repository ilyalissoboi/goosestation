// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "achievements_bridge.h"
#include "android_host.h"
#include "emu_thread.h"
#include "game_codes.h"

#include "core/achievements_private.h"
#include "core/cheats.h"
#include "core/host.h"
#include "core/settings.h"
#include "core/system.h"

#include "common/error.h"
#include "common/log.h"
#include "common/string_util.h"

#include "rc_client.h"

#include <charconv>

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

AchievementsBridge::GameSummary AchievementsBridge::GetGameSummary()
{
  GameSummary summary = {Achievements::AndroidGameStatus::Off, 0, 0, 0, 0, false, false, false};
  EmuThread::RunOnThread(
    [&summary]() {
      summary.status = Achievements::GetAndroidGameStatus();
      summary.hardcore = Achievements::IsHardcoreModeActive();
      const auto lock = Achievements::GetLock();
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
  rc_client_set_host(nullptr, host.empty() ? nullptr : host.c_str());
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
  // No patch code applies in hardcore mode; the player's switches come back with it off.
  if (enabled)
  {
    if (!System::IsShutdown())
      System::SetCheatList(nullptr);
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

  AndroidHost::NotifyAchievementNotification(key, title, text, image_path, seconds, points);
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
