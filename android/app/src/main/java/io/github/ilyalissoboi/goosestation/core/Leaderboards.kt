// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/** One of the running game's leaderboards: its [title] and [description]. */
class Leaderboard(
    val id: Int,
    val title: String,
    val description: String,
)

/** One entry of a leaderboard: its [rank], the [user]'s name and their [score], as the server formats it. */
class LeaderboardEntry(
    val rank: Int,
    val user: String,
    val score: String,
)

/**
 * A leaderboard as [NativeLibrary.fetchLeaderboard] fetches it: its [total] entries, the [top] ones, and the ones
 * [around] the signed-in player, theirs at [youIndex] (-1 when they have no entry).
 */
class LeaderboardPage(
    val total: Int,
    val top: Array<LeaderboardEntry>,
    val around: Array<LeaderboardEntry>,
    val youIndex: Int,
)
