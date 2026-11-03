// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <functional>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

class HTTPDownloader;
struct rc_api_host_t;

/// RetroAchievements without a connection: the unlocks and leaderboard entries rcheevos sends are kept on disk until
/// the server has them (achievements_offline_store.h). On only once the host gives it a folder (Enable()); only
/// Android does. Every call but Enable() is made with the achievements lock held, on the CPU thread.
namespace AchievementsOffline {

/// What the host hears about, on the CPU thread.
struct Callbacks
{
  std::function<void()> changed;                        // the records waiting changed
  std::function<void(std::string_view title)> refused;  // the server won't take one, which is dropped
  std::function<void(u32 count)> sent;                  // every record a user had waiting has been sent
};

/// Turns the module on, keeping its files in folder. Before Achievements::Initialize().
void Enable(std::string folder, Callbacks callbacks);

bool IsEnabled();

/// A request rcheevos is sending (its form fields): an unlock or entry is recorded before it goes, unless it's one of
/// rcheevos's retries (with "o"), which is recorded already. title names it, for a refusal.
void OnRequest(const std::map<std::string, std::string>& form, std::string_view title, s64 now);

/// The answer to a request: an unlock or entry the server has, or refuses, is cleared; one it hasn't got stays.
void OnAnswer(const std::map<std::string, std::string>& form, s32 status, std::string_view body);

/// Sends each of user's records that no request is out for (rcheevos's retries, which last the run, or an earlier
/// SendWaiting()'s), dated with how long ago it happened, through http to host (nullptr for RetroAchievements).
void SendWaiting(HTTPDownloader& http, const rc_api_host_t* host, std::string_view user, std::string_view token,
                 s64 now);

/// Forgets which records have a request out: rcheevos's retries ended (a sign-out).
void ForgetInFlight();

/// The running game started offline, from what was saved online: its sign-in or its set was answered from the store.
bool InSession();
void BeginSession();
void EndSession();

/// The answer for a request that couldn't reach the server, from what was saved online (OfflineAnswer()), for the
/// game booting (booting_hash); nullopt when there's none.
std::optional<std::string> AnswerOffline(const std::map<std::string, std::string>& form, std::string_view booting_hash,
                                         s64 now);

/// How many records are waiting, for each user with any (as first written).
std::vector<std::pair<std::string, u32>> CountsByUser();

/// How many of user's records are for the game of hash: that disc's, or any disc of the same game seen online.
u32 CountForGame(std::string_view user, std::string_view hash);

/// Saves a real sign-in's account, for a later one built offline: its name, display name, avatar and points.
void RememberUser(std::string_view user, std::string_view display_name, std::string_view avatar_url, u32 score,
                  u32 softcore_score);

} // namespace AchievementsOffline
