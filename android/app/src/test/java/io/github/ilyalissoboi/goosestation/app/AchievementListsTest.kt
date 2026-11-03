// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.Achievement
import io.github.ilyalissoboi.goosestation.core.AchievementSection
import io.github.ilyalissoboi.goosestation.core.GameAchievements
import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementListsTest {
    private fun game(
        status: Int,
        achievements: Boolean = true,
        leaderboards: Boolean = true,
    ) = GameAchievements(status, 14, 40, 85, 400, achievements, leaderboards, false)

    private fun cheevo(
        id: Int,
        unlockedAt: Long = 0,
    ) = Achievement(id, "A$id", "", 5, "", unlockedAt, "", 0f)

    private fun section(
        bucket: Int,
        vararg achievements: Achievement,
    ) = AchievementSection(bucket, "Bucket $bucket", arrayOf(*achievements))

    @Test
    fun rowStatusForEachState() {
        assertEquals("Sign in from Settings", AchievementLists.rowStatus(game(GameAchievements.OFF)))
        assertEquals("Loading…", AchievementLists.rowStatus(game(GameAchievements.LOADING)))
        assertEquals("Couldn't reach RetroAchievements", AchievementLists.rowStatus(game(GameAchievements.UNREACHABLE)))
        assertEquals("No achievements for this game", AchievementLists.rowStatus(game(GameAchievements.NO_SET)))
        assertEquals("14 of 40", AchievementLists.rowStatus(game(GameAchievements.READY)))
    }

    @Test
    fun leaderboardsOnly() {
        assertEquals("Leaderboards", AchievementLists.rowStatus(game(GameAchievements.READY, achievements = false)))
    }

    @Test
    fun unlockedIsNewestFirst() {
        val sections =
            AchievementLists.sections(
                listOf(
                    section(AchievementLists.UNLOCKED, cheevo(1, 100), cheevo(2, 300), cheevo(3, 200), cheevo(4, 300)),
                ),
            )

        assertEquals(listOf(2, 4, 3, 1), sections.single().achievements.map { it.id })
    }

    @Test
    fun unsyncedIsNamed() {
        val sections = AchievementLists.sections(listOf(section(AchievementLists.UNSYNCED, cheevo(1, 100))))

        assertEquals("Not sent yet", sections.single().label)
    }

    @Test
    fun emptySectionsAreDropped() {
        val sections = AchievementLists.sections(listOf(section(1), section(AchievementLists.UNLOCKED, cheevo(1, 5))))

        assertEquals(listOf(AchievementLists.UNLOCKED), sections.map { it.bucket })
    }

    @Test
    fun otherSectionsKeepTheirOrder() {
        val sections =
            AchievementLists.sections(
                listOf(section(6, cheevo(1)), section(7, cheevo(3), cheevo(2)), section(1, cheevo(5), cheevo(4))),
            )

        assertEquals(listOf(6, 7, 1), sections.map { it.bucket })
        assertEquals(listOf(3, 2), sections[1].achievements.map { it.id })
        assertEquals("Bucket 7", sections[1].label)
    }

    @Test
    fun rowCountsWhatsWaiting() {
        val waiting = GameAchievements(GameAchievements.READY, 14, 40, 85, 400, true, true, false, waiting = 2)
        assertEquals("14 of 40 · 2 to send", AchievementLists.rowStatus(waiting))
        assertEquals("14 of 40", AchievementLists.rowStatus(game(GameAchievements.READY)))
    }
}
