// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementNoticesTest {
    @Test
    fun unlockKey() {
        assertEquals(
            AchievementNotice.Card(CardKind.Unlock, "Sparx Rescue", "Free the first dragon", "/b.png", 5),
            AchievementNotices.from("achievement_unlock_42", "Sparx Rescue", "Free the first dragon", "/b.png", 5),
        )
    }

    @Test
    fun unlockWithoutPoints() {
        assertEquals(
            AchievementNotice.Card(CardKind.Unlock, "Sparx Rescue", "", "", null),
            AchievementNotices.from("achievement_unlock_42", "Sparx Rescue", "", "", -1),
        )
    }

    @Test
    fun masteryKey() {
        assertEquals(
            AchievementNotice.Card(CardKind.Mastery, "Mastered Spyro", "40 achievements, 400 points", "/g.png", null),
            AchievementNotices.from(
                "achievement_mastery",
                "Mastered Spyro",
                "40 achievements, 400 points",
                "/g.png",
                -1,
            ),
        )
    }

    @Test
    fun summaryKey() {
        assertEquals(
            AchievementNotice.Message("You have unlocked 14 of 40 achievements.", "/g.png"),
            AchievementNotices.from(
                "achievement_summary",
                "Spyro",
                "You have unlocked 14 of 40 achievements.",
                "/g.png",
                -1,
            ),
        )
    }

    @Test
    fun leaderboardKey() {
        assertEquals(
            AchievementNotice.Message("Sunny Flight: Leaderboard attempt started.", "/g.png"),
            AchievementNotices.from("leaderboard_31", "Sunny Flight", "Leaderboard attempt started.", "/g.png", -1),
        )
    }

    @Test
    fun unknownKey() {
        assertEquals(
            AchievementNotice.Message("Title: Text", null),
            AchievementNotices.from("other", "Title", "Text", "", -1),
        )
        assertEquals(AchievementNotice.Message("Text", null), AchievementNotices.from("other", "", "Text", "", -1))
    }

    @Test
    fun toastUsesTitleElseMessage() {
        assertEquals(
            "Achievements Disconnected",
            AchievementNotices.toast("Achievements Disconnected", "An unlock request…"),
        )
        assertEquals("Hardcore mode is now enabled.", AchievementNotices.toast("", "Hardcore mode is now enabled."))
    }

    @Test
    fun offlineUnlockHasTheNote() {
        assertEquals(
            AchievementNotice.Card(CardKind.Unlock, "Sparx Rescue", "", "", 5, note = "Sent when you're online"),
            AchievementNotices.from("achievement_unlock_42", "Sparx Rescue", "", "", 5, offline = true),
        )
    }

    @Test
    fun onlineUnlockHasNone() {
        assertEquals(
            null,
            (
                AchievementNotices.from(
                    "achievement_unlock_42",
                    "Sparx Rescue",
                    "",
                    "",
                    5,
                ) as AchievementNotice.Card
            ).note,
        )
    }
}
