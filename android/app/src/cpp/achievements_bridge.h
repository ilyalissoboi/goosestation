// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "core/achievements.h"

#include "common/types.h"

#include <optional>
#include <string>
#include <vector>

/// RetroAchievements for the app: the account, signing in and out. Each call runs on the CPU thread and waits for it.
namespace AchievementsBridge {

struct Account
{
  Achievements::AndroidSignIn state;
  std::string username;     // the login name, from settings.ini
  std::string display_name; // the server's, when signed in
  u32 score;                // hardcore points, when signed in
  u32 softcore_score;
};

Account GetAccount();

/// The unlocks and leaderboard entries waiting to be sent: the stored account's ([Cheevos] Username), and each other
/// account's, with how many.
struct Queue
{
  u32 own;
  std::vector<std::pair<std::string, u32>> others;
};
Queue GetQueue();

/// Turns achievements on and signs in, waiting for the server: nullopt when signed in, else why not (the server's
/// words, or "Couldn't reach RetroAchievements"). A failed sign-in leaves achievements as they were.
std::optional<std::string> SignIn(std::string username, std::string password);

/// Signs out, forgetting the username and token, and turns achievements off.
void SignOut();

/// Whether a game booted now would start in hardcore mode: achievements and hardcore are on, and a sign-in is stored.
/// On the CPU thread.
bool HardcoreWouldStart();

/// Hardcore mode is active in the running game. On the CPU thread.
bool IsHardcoreActive();

/// The running game's achievements, for the menu's row and panel. In hardcore, the counts are the hardcore ones.
struct GameSummary
{
  Achievements::AndroidGameStatus status;
  u32 unlocked;
  u32 total;
  u32 points;
  u32 total_points;
  bool has_achievements;
  bool has_leaderboards;
  bool hardcore;
  bool offline; // started without a connection, from the set saved at an online start
  u32 waiting;  // the signed-in account's unlocks and entries for this game, waiting to be sent
};
GameSummary GetGameSummary();

struct AchievementItem
{
  u32 id;
  std::string title;
  std::string description;
  std::string badge_path; // in the core's cache; downloaded in the background if it isn't there yet
  u32 points;
  s64 unlocked_at; // Unix seconds, 0 while locked
  std::string progress;
  float percent;
};

struct AchievementSection
{
  u8 bucket; // rcheevos's RC_CLIENT_ACHIEVEMENT_BUCKET_*
  std::string label;
  std::vector<AchievementItem> items;
};

/// The running game's achievements in rcheevos's sections by progress, in its order; empty without a set.
std::vector<AchievementSection> GetAchievementSections();

struct LeaderboardItem
{
  u32 id;
  std::string title;
  std::string description;
};

/// The running game's leaderboards, in the set's order, without those the client can't track.
std::vector<LeaderboardItem> GetLeaderboards();

struct LeaderboardEntry
{
  u32 rank;
  std::string user;
  std::string score;
};

struct LeaderboardPage
{
  u32 total;
  std::vector<LeaderboardEntry> top;
  std::vector<LeaderboardEntry> around;
  s32 you_index; // in around, -1 for no entry
};

/// Starts fetching leaderboard id's top 10 and the 9 entries around the player, and returns. The answer goes to
/// AndroidHost::NotifyLeaderboard() once, with request: the page, or why there's none.
void FetchLeaderboard(u32 id, s32 request);

/// For the instrumented tests only: the server rcheevos talks to, or the real one with an empty host.
void SetServerForTest(const std::string& host);

} // namespace AchievementsBridge
