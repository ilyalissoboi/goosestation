// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.AchievementSection
import io.github.ilyalissoboi.goosestation.core.GameAchievements

/** The menu's Achievements row and tab, from what the core gives. */
object AchievementLists {
    /** rcheevos's bucket of unlocked achievements, and of unlocks not yet sent to the server. */
    const val UNLOCKED = 2
    const val UNSYNCED = 8

    /** What the menu's Achievements row says under its label. */
    fun rowStatus(game: GameAchievements): String =
        when (game.status) {
            GameAchievements.OFF -> "Sign in from Settings"
            GameAchievements.LOADING -> "Loading…"
            GameAchievements.UNREACHABLE -> "Couldn't reach RetroAchievements"
            GameAchievements.NO_SET -> "No achievements for this game"
            else ->
                (if (game.hasAchievements) "${game.unlocked} of ${game.total}" else "Leaderboards") +
                    AchievementQueueWords.rowSuffix(game.waiting)
        }

    /**
     * The sections as the tab shows them: in rcheevos's order, empty ones left out, unlocked achievements newest first
     * (rcheevos keeps them in the set's order), and unsynced unlocks called "Not sent yet".
     */
    fun sections(list: List<AchievementSection>): List<AchievementSection> =
        list
            .filter { it.achievements.isNotEmpty() }
            .map { section ->
                when (section.bucket) {
                    UNLOCKED ->
                        AchievementSection(
                            section.bucket,
                            section.label,
                            section.achievements.sortedByDescending { it.unlockedAt }.toTypedArray(),
                        )
                    UNSYNCED -> AchievementSection(section.bucket, "Not sent yet", section.achievements)
                    else -> section
                }
            }
}
