// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The game the in-game menu leaves for, from a shared slot of another game: [start]ed once the running one has
 * stopped and its memory cards are synced, on [scope]. Main thread only.
 */
class GameSwitch(
    private val scope: CoroutineScope,
    private val start: (StateStart) -> Unit,
) {
    private var next: StateStart? = null

    /** The running game is being left for [to]. */
    fun leaving(to: StateStart) {
        next = to
    }

    /**
     * The game stopped: [sync] runs, then the game it was left for, if any, starts. It's taken now, so a sync that
     * fails drops it rather than leaving it for a later, unrelated stop.
     */
    fun stopped(sync: suspend () -> Unit) {
        val to = next
        next = null
        scope.launch {
            sync()
            to?.let(start)
        }
    }
}
