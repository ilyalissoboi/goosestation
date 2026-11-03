// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * The running game's RetroAchievements, as [NativeLibrary.getGameAchievements] reads them: its [status] (one of the
 * constants below), how many achievements and points are [unlocked] of the [total] (in hardcore, the hardcore ones),
 * whether it has achievements or leaderboards, whether hardcore mode is active, and whether it started [offline], from
 * the set saved at an online start (casual only).
 */
class GameAchievements(
    val status: Int,
    val unlocked: Int,
    val total: Int,
    val points: Int,
    val totalPoints: Int,
    val hasAchievements: Boolean,
    val hasLeaderboards: Boolean,
    val hardcore: Boolean,
    val offline: Boolean = false,
) {
    companion object {
        /** Achievements are off, or nobody is signed in. */
        const val OFF = 0
        const val LOADING = 1
        const val UNREACHABLE = 2

        /** The game is unknown to RetroAchievements, or has nothing in its set. */
        const val NO_SET = 3
        const val READY = 4
    }
}

/**
 * One achievement: its [title], [description], [points] and badge ([badgePath], a PNG in the core's cache, which may
 * not be downloaded yet), when it was unlocked ([unlockedAt], Unix seconds, 0 while locked), and its [progress] count
 * ("312/500") and [percent] where the set tracks one.
 */
class Achievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val badgePath: String,
    val unlockedAt: Long,
    val progress: String,
    val percent: Float,
)

/** One of rcheevos's sections of a game's achievements: its [bucket] type, its [label], and its [achievements]. */
class AchievementSection(
    val bucket: Int,
    val label: String,
    val achievements: Array<Achievement>,
)
