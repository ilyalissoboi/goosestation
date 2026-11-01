// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "core/achievements.h"

#include "common/types.h"

#include <optional>
#include <string>

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

/// Turns achievements on and signs in, waiting for the server: nullopt when signed in, else why not (the server's
/// words, or "Couldn't reach RetroAchievements"). A failed sign-in leaves achievements as they were.
std::optional<std::string> SignIn(std::string username, std::string password);

/// Signs out, forgetting the username and token, and turns achievements off.
void SignOut();

/// For the instrumented tests only: the server rcheevos talks to, or the real one with an empty host.
void SetServerForTest(const std::string& host);

} // namespace AchievementsBridge
