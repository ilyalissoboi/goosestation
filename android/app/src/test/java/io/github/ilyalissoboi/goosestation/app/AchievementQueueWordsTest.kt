// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** The words for achievements waiting to be sent, as the design gives them. */
class AchievementQueueWordsTest {
    @Test
    fun fixedLines() {
        assertEquals("Sent when you're online", AchievementQueueWords.CARD_NOTE)
        assertEquals(
            "Offline: achievements are saved and sent later (casual mode)",
            AchievementQueueWords.OFFLINE_START,
        )
        assertEquals("Not available offline", AchievementQueueWords.LEADERBOARDS_OFFLINE)
    }

    @Test
    fun rowSuffix() {
        assertEquals("", AchievementQueueWords.rowSuffix(0))
        assertEquals(" · 1 to send", AchievementQueueWords.rowSuffix(1))
        assertEquals(" · 2 to send", AchievementQueueWords.rowSuffix(2))
    }

    @Test
    fun ownWaiting() {
        assertEquals("1 unlock waiting to be sent", AchievementQueueWords.ownWaiting(1))
        assertEquals("2 unlocks waiting to be sent", AchievementQueueWords.ownWaiting(2))
    }

    @Test
    fun othersKept() {
        assertEquals("1 unlock for swan is kept until swan signs in again", AchievementQueueWords.othersKept("swan", 1))
        assertEquals(
            "2 unlocks for goose are kept until goose signs in again",
            AchievementQueueWords.othersKept("goose", 2),
        )
    }

    @Test
    fun expiry() {
        assertEquals("RetroAchievements sign-in expired: sign in again in Settings", AchievementQueueWords.expiry(0))
        assertEquals(
            "RetroAchievements sign-in expired: sign in again in Settings. " +
                "1 unlock is kept and sent once you sign in again",
            AchievementQueueWords.expiry(1),
        )
        assertEquals(
            "RetroAchievements sign-in expired: sign in again in Settings. " +
                "2 unlocks are kept and sent once you sign in again",
            AchievementQueueWords.expiry(2),
        )
    }

    @Test
    fun sent() {
        assertEquals("1 unlock sent to RetroAchievements", AchievementQueueWords.sent(1))
        assertEquals("3 unlocks sent to RetroAchievements", AchievementQueueWords.sent(3))
    }

    @Test
    fun refused() =
        assertEquals(
            "Couldn't send 'Ace Pilot': RetroAchievements refused it",
            AchievementQueueWords.refused("Ace Pilot"),
        )
}
