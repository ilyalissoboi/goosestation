// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A game's start: it waits for the settings work asked for before it ([settle]), then runs the start's checks and, if
 * they pass, boots. Waiting first means nothing has left the library yet if the checks refuse it. A second start while
 * one waits is ignored. Main thread only.
 */
class GameStartGate(
    private val scope: CoroutineScope,
    private val settle: suspend () -> Unit,
) {
    /** A start is waiting for its settings. */
    var starting = false
        private set

    /** Waits for the settings, then boots with [boot] if [begin]'s checks pass. Ignored while a start waits. */
    fun start(
        begin: () -> Boolean,
        boot: () -> Unit,
    ) {
        if (starting) return
        starting = true
        scope.launch {
            try {
                settle()
                if (begin()) boot()
            } finally {
                starting = false
            }
        }
    }
}
