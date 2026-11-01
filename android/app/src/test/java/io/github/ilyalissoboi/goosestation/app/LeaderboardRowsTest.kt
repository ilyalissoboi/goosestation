// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.LeaderboardEntry
import io.github.ilyalissoboi.goosestation.core.LeaderboardPage
import org.junit.Assert.assertEquals
import org.junit.Test

class LeaderboardRowsTest {
    private fun entries(ranks: IntRange) = ranks.map { LeaderboardEntry(it, "user$it", "0:${it}0") }.toTypedArray()

    private fun page(
        top: IntRange,
        around: IntRange?,
        you: Int,
    ): LeaderboardPage {
        val near = around?.let(::entries) ?: emptyArray()
        return LeaderboardPage(1284, entries(top), near, near.indexOfFirst { it.rank == you })
    }

    /** The rows as text: "Top", "1", "212*" (yours), "none". */
    private fun shown(page: LeaderboardPage) =
        LeaderboardRows.rows(page).map {
            when (it) {
                is LeaderboardRow.Heading -> it.label
                is LeaderboardRow.Entry -> "${it.entry.rank}${if (it.isYou) "*" else ""}"
                LeaderboardRow.NoEntry -> "none"
            }
        }

    @Test
    fun topAndAroundApart() {
        assertEquals(
            listOf("Top") + (1..10).map { "$it" } + "Around you" + (208..216).map { if (it == 212) "212*" else "$it" },
            shown(page(1..10, 208..216, you = 212)),
        )
    }

    @Test
    fun overlapMergesWithoutRepeats() {
        assertEquals(
            listOf("Top") + (1..14).map { if (it == 10) "10*" else "$it" },
            shown(page(1..10, 6..14, you = 10)),
        )
    }

    @Test
    fun adjacentMerges() {
        assertEquals(
            listOf("Top") + (1..19).map { if (it == 15) "15*" else "$it" },
            shown(page(1..10, 11..19, you = 15)),
        )
    }

    @Test
    fun noEntryOfYours() {
        assertEquals(listOf("Top") + (1..10).map { "$it" } + "none", shown(page(1..10, null, you = -1)))
    }

    @Test
    fun youInTheTopAreMarked() {
        assertEquals(
            listOf("Top") + (1..10).map { if (it == 3) "3*" else "$it" },
            shown(page(1..10, 1..7, you = 3)),
        )
    }
}
