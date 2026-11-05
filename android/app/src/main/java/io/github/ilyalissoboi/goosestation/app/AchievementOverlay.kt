// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What's over the game for RetroAchievements: the unlock [card] showing, one at a time from a queue, and the live
 * [indicators]. Neither shows while the menu is open, and a card's time only runs while it's closed. Times are by
 * [clock] (in ms); [expire] after [nextExpiry]. Main thread only.
 */
class AchievementOverlay(
    private val clock: () -> Long,
) {
    /** A live indicator: a leaderboard [TRACKER]'s value, a [CHALLENGE]'s badge, or the [PROGRESS] pop-up. */
    data class Indicator(
        val kind: Int,
        val id: Int,
        val text: String,
        val badgePath: String,
        val untilMs: Long? = null,
    )

    var card by mutableStateOf<AchievementNotice.Card?>(null)
        private set

    var indicators by mutableStateOf(emptyList<Indicator>())
        private set

    private val queue = ArrayDeque<AchievementNotice.Card>()
    private var current: AchievementNotice.Card? = null
    private var currentUntil = 0L

    // The current card's time left, while the menu is open.
    private var pausedLeft: Long? = null
    private var menuOpen = false
    private val live = mutableListOf<Indicator>()

    /** Queues [card], to show once those before it have. */
    fun post(card: AchievementNotice.Card) {
        queue.addLast(card)
        if (current == null) next()
    }

    /** A live indicator shows or changes ([shown]), or goes. */
    fun indicator(
        kind: Int,
        id: Int,
        shown: Boolean,
        text: String,
        badgePath: String,
    ) {
        // One progress pop-up at a time, whichever achievement it's for.
        val same: (Indicator) -> Boolean = { it.kind == kind && (kind == PROGRESS || it.id == id) }
        val index = live.indexOfFirst(same)
        when {
            !shown -> live.removeAll(same)
            else -> {
                val until = if (kind == PROGRESS) clock() + PROGRESS_MS else null
                val indicator = Indicator(kind, id, text, badgePath, until)
                if (index >= 0) live[index] = indicator else live += indicator
            }
        }
        publish()
    }

    /** The in-game menu opened or closed. */
    fun menuOpen(open: Boolean) {
        if (open == menuOpen) return
        menuOpen = open
        if (open) {
            if (current != null) pausedLeft = (currentUntil - clock()).coerceAtLeast(0)
        } else {
            pausedLeft?.let { currentUntil = clock() + it }
            pausedLeft = null
        }
        publish()
    }

    /** When the next card or pop-up's time is up, or null with none running. */
    fun nextExpiry(): Long? =
        listOfNotNull(
            currentUntil.takeIf { current != null && !menuOpen },
            live.mapNotNull { it.untilMs }.minOrNull(),
        ).minOrNull()

    /** Takes away what's had its time, and shows the next card. */
    fun expire() {
        val now = clock()
        if (current != null && !menuOpen && currentUntil <= now) next()
        live.removeAll { it.untilMs != null && it.untilMs <= now }
        publish()
    }

    /** Forgets everything, as the game ends. */
    fun clear() {
        queue.clear()
        current = null
        pausedLeft = null
        live.clear()
        publish()
    }

    private fun next() {
        val card = queue.removeFirstOrNull()
        current = card
        if (card != null) {
            val duration = card.durationMs
            if (menuOpen) pausedLeft = duration else currentUntil = clock() + duration
        }
        publish()
    }

    private fun publish() {
        card = if (menuOpen) null else current
        indicators = if (menuOpen) emptyList() else live.toList()
    }

    companion object {
        const val TRACKER = 0
        const val CHALLENGE = 1
        const val PROGRESS = 2

        const val UNLOCK_MS = 5_000L
        const val MASTERY_MS = 10_000L
        const val PROGRESS_MS = 3_000L
    }
}
