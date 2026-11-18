// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "achievements.h"

#include "common/types.h"

#include <string>
#include <string_view>
#include <vector>

/// What desktop (Qt and Big Picture) says about unlocks and leaderboard entries kept without a connection, both
/// counted as unlocks: Android's words, word for word (AchievementQueueWords.kt).
namespace AchievementsOfflineWords {

/// Under an unlock's notification, in a game started offline.
std::string CardNote();

/// Once, when a game starts from its saved set without a connection.
std::string OfflineStart();

/// The leaderboards screen, in a game started offline.
std::string LeaderboardsOffline();

/// Turning Hardcore on in a game started offline.
std::string HardcoreStaysOff();

/// The account, while a stored token's sign-in can't reach the server.
std::string NotConnected();

/// The account, while a game started offline runs.
std::string OfflineSignedIn(std::string_view user);

/// Qt's status bar, for a sign-in built offline.
std::string StatusBarOffline(std::string_view user);

/// After the Achievements button and summary: " · 2 to send", or nothing with none waiting.
std::string ToSendSuffix(u32 count);

/// Added to the message of a token the server refused: what it keeps, or nothing with none waiting.
std::string KeptUntilSignIn(u32 count);

std::string Sent(u32 count);
std::string Refused(std::string_view title);

/// The lines the Achievements settings page shows under the account, in order: the offline session or the missing
/// connection, then a line for each user's waiting records.
std::vector<std::string> AccountNotes(const Achievements::AccountStatus& status);

} // namespace AchievementsOfflineWords
