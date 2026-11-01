// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.LeaderboardEntry
import io.github.ilyalissoboi.goosestation.core.LeaderboardPage

/** A row of an open leaderboard: a heading, an entry (the player's own marked), or the player's lack of one. */
sealed interface LeaderboardRow {
    data class Heading(
        val label: String,
    ) : LeaderboardRow

    data class Entry(
        val entry: LeaderboardEntry,
        val isYou: Boolean,
    ) : LeaderboardRow

    data object NoEntry : LeaderboardRow
}

/** An open leaderboard's rows. */
object LeaderboardRows {
    /**
     * "Top", the top entries, then "Around you" and the entries around the player. When those reach the top ones or
     * follow straight on from them, it's one list, each rank once. Without an entry of the player's, the top ones and
     * then [LeaderboardRow.NoEntry].
     */
    fun rows(page: LeaderboardPage): List<LeaderboardRow> {
        val you = page.around.getOrNull(page.youIndex)

        fun entry(it: LeaderboardEntry) =
            LeaderboardRow.Entry(
                it,
                you != null && it.rank == you.rank && it.user == you.user,
            )
        val rows = mutableListOf<LeaderboardRow>(LeaderboardRow.Heading("Top"))
        if (you == null) {
            page.top.mapTo(rows, ::entry)
            rows += LeaderboardRow.NoEntry
            return rows
        }
        val lastTop = page.top.lastOrNull()?.rank ?: 0
        val firstAround = page.around.firstOrNull()?.rank ?: Int.MAX_VALUE
        if (firstAround <= lastTop + 1) {
            (page.top.toList() + page.around).distinctBy { it.rank }.sortedBy { it.rank }.mapTo(rows, ::entry)
        } else {
            page.top.mapTo(rows, ::entry)
            rows += LeaderboardRow.Heading("Around you")
            page.around.mapTo(rows, ::entry)
        }
        return rows
    }
}
