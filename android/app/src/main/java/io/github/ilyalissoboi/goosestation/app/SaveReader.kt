// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameDisc
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Reads the slot lists' rows through [api], each call on [game]: a game's own slots, the states still under its other
 * discs, the shared slots, and each row's picture, disc and game. For the in-game slot panel and the Saves page.
 */
class SaveReader(
    private val api: GameCoreApi,
    private val game: CoroutineDispatcher,
) {
    /** [key]'s slots, as [mode] lists them ([SaveLists.entries]). */
    suspend fun own(
        key: String,
        mode: SlotMode,
    ): List<SlotEntry> = withContext(game) { SaveLists.entries(mode, api.getSaveStateTimes(key)) }

    /**
     * The saves still filed under [discs]' serials other than [key], as the migration left them or desktop saved them:
     * each with its [SlotEntry.owner] and disc.
     */
    suspend fun others(
        key: String,
        discs: List<GameDisc>,
    ): List<SlotEntry> {
        val serials = discs.map { it.serial }.filter { it.isNotBlank() && it != key }.distinct()
        val times = withContext(game) { serials.associateWith { api.getSaveStateTimes(it) } }
        return times.flatMap { (owner, ownerTimes) ->
            val disc = discs.firstOrNull { it.serial == owner }?.number
            SaveLists.entries(SlotMode.Load, ownerTimes).map { it.copy(owner = owner, disc = disc) }
        }
    }

    /** The shared slots, as [mode] lists them ([SaveLists.shared]). */
    suspend fun shared(mode: SlotMode): List<SlotEntry> =
        withContext(game) { SaveLists.shared(mode, api.getSharedStateTimes()) }

    /**
     * [entry] with what its state shows (its picture, its disc in a [multiDisc] game, and, for a shared slot, the game
     * it holds and its disc there), in the game at [gamePath] whose key is [key]; null when the state shows nothing.
     */
    suspend fun preview(
        entry: SlotEntry,
        key: String,
        gamePath: String,
        multiDisc: Boolean,
    ): SlotEntry? {
        val preview = withContext(game) { api.getStatePreview(entry.ref(key), gamePath) } ?: return null
        if (entry.shared) {
            val holds = SaveLists.held(preview)
            return entry.copy(picture = preview.picture, holds = holds, disc = preview.disc.takeIf { it > 0 })
        }
        val disc = preview.disc.takeIf { multiDisc && it > 0 }
        if (preview.picture == null && disc == null) return null
        return entry.copy(picture = preview.picture, disc = disc ?: entry.disc)
    }
}
