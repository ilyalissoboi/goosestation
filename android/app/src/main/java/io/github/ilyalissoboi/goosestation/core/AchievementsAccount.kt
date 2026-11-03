// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * The RetroAchievements account, as [NativeLibrary.getAchievementsAccount] reads it: its sign-in [state] (one of the
 * constants below), the [username] from settings.ini, and, once signed in, the server's [displayName] and points
 * ([score] in hardcore, [softcoreScore] in softcore).
 */
class AchievementsAccount(
    val state: Int,
    val username: String,
    val displayName: String,
    val score: Int,
    val softcoreScore: Int,
) {
    companion object {
        const val SIGNED_OUT = 0
        const val CONNECTING = 1

        /** A username and token are stored, but the sign-in hasn't reached the server (no connection). */
        const val NOT_CONNECTED = 2
        const val SIGNED_IN = 3
    }
}

/**
 * The unlocks and leaderboard entries waiting to be sent, as [NativeLibrary.getAchievementsQueue] reads them: [own], the
 * signed-in (or stored) account's, and each other account's ([otherUsers], with [otherCounts]), sent only once that
 * account signs in again.
 */
class AchievementsQueue(
    val own: Int,
    val otherUsers: Array<String>,
    val otherCounts: IntArray,
) {
    fun others(): List<Pair<String, Int>> = otherUsers.zip(otherCounts.toList())

    companion object {
        val NONE = AchievementsQueue(0, emptyArray(), IntArray(0))
    }
}
