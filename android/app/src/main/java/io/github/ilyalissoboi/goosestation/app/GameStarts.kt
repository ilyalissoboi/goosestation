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
 * Starting a game: at once, or, when it has a resume state, once the player has answered [offer]. The game's state and
 * its last disc ([records]) are looked for on [game], with [api]; [start] boots the game on that disc, or from its
 * resume state. A multi-disc game's discs' states are first moved under its key, once ([StateMigration]), with their
 * [lastSlots]. Main thread only.
 */
class GameStarts(
    private val scope: CoroutineScope,
    private val game: CoroutineDispatcher,
    private val api: GameCoreApi,
    private val records: GameRecordStore,
    private val lastSlots: LastSlotStore,
    private val start: (game: GameEntry, disc: Int, resume: Boolean) -> Unit,
) {
    /** The question being asked, or null. */
    var offer by mutableStateOf<ResumeOffer?>(null)
        private set

    private var checking = false

    /** The disc the offered game starts on if the player starts over. */
    private var offerDisc = 1

    /**
     * Starts [entry] on the disc last played in it (else its first), or asks first if it has a resume state. A game
     * without an ID (a serial, or the core's content hash for a PS-EXE or a disc the database doesn't know) has none,
     * and starts at once. A play while one is being looked at, or asked about, does nothing.
     */
    fun play(entry: GameEntry) {
        if (checking || offer != null) return
        val serial = entry.serial
        if (serial.isBlank()) return start(entry, 1, false)
        checking = true
        scope.launch {
            try {
                val (disc, found) =
                    withContext(game) {
                        if (entry.discs.size > 1 && !records.migrated(serial)) migrate(entry)
                        val disc = records.lastDisc(serial) ?: 1
                        val savedAt = api.getSaveStateTimes(serial)[0]
                        disc to
                            if (savedAt == 0L) {
                                null
                            } else {
                                ResumeOffer(entry, savedAt, api.getSaveStatePreview(serial, SaveSlots.RESUME)?.picture)
                            }
                    }
                offerDisc = disc
                if (found == null) start(entry, disc, false) else offer = found
            } finally {
                checking = false
            }
        }
    }

    /**
     * Moves [entry]'s discs' states under its key, losing none, and records that it's done. A move that fails stops it
     * unrecorded, so the next play tries again. On [game].
     */
    private fun migrate(entry: GameEntry) {
        val key = entry.serial
        val serials = (listOf(key) + entry.discs.map { it.serial }).filter { it.isNotBlank() }.distinct()
        val times = serials.associateWith { api.getSaveStateTimes(it) }
        val moves = StateMigration.plan(key, times)
        for (move in moves) {
            if (!api.moveSaveState(move.serial, move.slot, key, move.toSlot)) return
        }
        val last = serials.mapNotNull { serial -> lastSlots.get(serial)?.let { serial to it } }.toMap()
        StateMigration.lastSlot(key, last, times, moves)?.let { lastSlots.set(key, it) }
        records.setMigrated(key)
    }

    /** Resume: the game boots from its resume state. */
    fun resume() {
        val offer = take() ?: return
        start(offer.game, offerDisc, true)
    }

    /** Start over: the game boots afresh. Its resume state stays until the next Exit game replaces it. */
    fun startOver() {
        val offer = take() ?: return
        start(offer.game, offerDisc, false)
    }

    /** Nothing starts. */
    fun cancel() {
        take()
    }

    private fun take(): ResumeOffer? = offer.also { offer = null }
}
