// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/achievements_offline_words.h"

#include <gtest/gtest.h>

using Achievements::AccountStatus;
using Achievements::SignInState;
namespace Words = AchievementsOfflineWords;

namespace {

AccountStatus Account(SignInState state, std::string user, std::vector<std::pair<std::string, u32>> waiting = {},
                      bool offline_session = false)
{
  return AccountStatus{state, offline_session, std::move(user), std::move(waiting)};
}

} // namespace

TEST(AchievementsOfflineWords, FixedWords)
{
  EXPECT_EQ(Words::CardNote(), "Sent when you're online");
  EXPECT_EQ(Words::OfflineStart(), "Offline: achievements are saved and sent later (casual mode)");
  EXPECT_EQ(Words::LeaderboardsOffline(), "Not available offline");
  EXPECT_EQ(Words::HardcoreStaysOff(), "Hardcore stays off: this game started offline.");
  EXPECT_EQ(Words::NotConnected(), "Not connected. RetroAchievements is checked again shortly.");
  EXPECT_EQ(Words::OfflineSignedIn("goose"), "Offline: signed in as goose until this game stops");
  EXPECT_EQ(Words::StatusBarOffline("goose"), "RA: Offline as goose");
}

TEST(AchievementsOfflineWords, Counts)
{
  EXPECT_EQ(Words::ToSendSuffix(2), " · 2 to send");
  EXPECT_EQ(Words::Sent(1), "1 unlock sent to RetroAchievements");
  EXPECT_EQ(Words::Sent(3), "3 unlocks sent to RetroAchievements");
  EXPECT_EQ(Words::Refused("Ace Pilot"), "Couldn't send 'Ace Pilot': RetroAchievements refused it");
  EXPECT_EQ(Words::KeptUntilSignIn(1), "1 unlock is kept and sent once you sign in again");
  EXPECT_EQ(Words::KeptUntilSignIn(2), "2 unlocks are kept and sent once you sign in again");
}

// Review Focus 3.
TEST(AchievementsOfflineWords, NothingWaitingSaysNothing)
{
  EXPECT_EQ(Words::ToSendSuffix(0), "");
  EXPECT_EQ(Words::KeptUntilSignIn(0), "");
  EXPECT_TRUE(Words::AccountNotes(Account(SignInState::SignedIn, "goose")).empty());
}

TEST(AchievementsOfflineWords, OwnRecordsAreWaiting)
{
  EXPECT_EQ(Words::AccountNotes(Account(SignInState::SignedIn, "goose", {{"goose", 2}})),
            std::vector<std::string>{"2 unlocks waiting to be sent"});
  EXPECT_EQ(Words::AccountNotes(Account(SignInState::SignedIn, "goose", {{"goose", 1}})),
            std::vector<std::string>{"1 unlock waiting to be sent"});
}

// Review Focus 1.
TEST(AchievementsOfflineWords, OwnRecordsInAnotherCaseAreOwn)
{
  EXPECT_EQ(Words::AccountNotes(Account(SignInState::SignedIn, "Goose", {{"goose", 2}})),
            std::vector<std::string>{"2 unlocks waiting to be sent"});
}

TEST(AchievementsOfflineWords, OthersAreKept)
{
  EXPECT_EQ(Words::AccountNotes(Account(SignInState::SignedIn, "duck", {{"goose", 1}, {"duck", 3}})),
            (std::vector<std::string>{"1 unlock for goose is kept until goose signs in again",
                                      "3 unlocks waiting to be sent"}));
}

TEST(AchievementsOfflineWords, SignedOutKeepsEveryonesRecords)
{
  EXPECT_EQ(Words::AccountNotes(Account(SignInState::SignedOut, "", {{"goose", 2}})),
            std::vector<std::string>{"2 unlocks for goose are kept until goose signs in again"});
}

TEST(AchievementsOfflineWords, NotConnectedSaysSoFirst)
{
  EXPECT_EQ(Words::AccountNotes(Account(SignInState::NotConnected, "goose", {{"goose", 2}})),
            (std::vector<std::string>{"Not connected. RetroAchievements is checked again shortly.",
                                      "2 unlocks waiting to be sent"}));
}

TEST(AchievementsOfflineWords, AnOfflineSessionSaysSoFirst)
{
  EXPECT_EQ(Words::AccountNotes(Account(SignInState::NotConnected, "goose", {{"goose", 1}}, true)),
            (std::vector<std::string>{"Offline: signed in as goose until this game stops",
                                      "1 unlock waiting to be sent"}));
}
