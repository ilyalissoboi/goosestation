// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <functional>
#include <map>
#include <string>
#include <string_view>

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

} // namespace AchievementsOffline
