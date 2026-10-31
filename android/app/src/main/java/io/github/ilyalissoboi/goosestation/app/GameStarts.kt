// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.GameEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Resume where you left off?" for [game]: its resume state, saved at [savedAt] (Unix seconds), and its [picture]. */
data class ResumeOffer(
    val game: GameEntry,
    val savedAt: Long,
    val picture: ArgbImage?,
)

/**
 * Starting a game: at once, or, when it has a resume state, once the player has answered [offer]. The game's state is
 * looked for on [game], with [api]; [start] boots the game, from its serial's resume state when given one. Main thread
 * only.
 */
class GameStarts(
    private val scope: CoroutineScope,
    private val game: CoroutineDispatcher,
    private val api: GameCoreApi,
    private val start: (GameEntry, String?) -> Unit,
) {
    /** The question being asked, or null. */
    var offer by mutableStateOf<ResumeOffer?>(null)
        private set

    private var checking = false

    /**
     * Starts [entry], or asks first if it has a resume state. A game without a serial has none, and starts at once. A
     * play while one is being looked at, or asked about, does nothing.
     */
    fun play(entry: GameEntry) {
        if (checking || offer != null) return
        val serial = entry.serial
        if (serial.isBlank()) return start(entry, null)
        checking = true
        scope.launch {
            try {
                val found =
                    withContext(game) {
                        val savedAt = api.getSaveStateTimes(serial)[0]
                        if (savedAt ==
                            0L
                        ) {
                            null
                        } else {
                            ResumeOffer(entry, savedAt, api.getSaveStateScreenshot(serial, SaveSlots.RESUME))
                        }
                    }
                if (found == null) start(entry, null) else offer = found
            } finally {
                checking = false
            }
        }
    }

    /** Resume: the game boots from its resume state. */
    fun resume() {
        val offer = take() ?: return
        start(offer.game, offer.game.serial)
    }

    /** Start over: the game boots afresh. Its resume state stays until the next Exit game replaces it. */
    fun startOver() {
        val offer = take() ?: return
        start(offer.game, null)
    }

    /** Nothing starts. */
    fun cancel() {
        take()
    }

    private fun take(): ResumeOffer? = offer.also { offer = null }
}
