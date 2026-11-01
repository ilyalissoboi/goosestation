// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/** The state in [serial]'s [slot] goes to the game key's [toSlot]. */
data class StateMove(
    val serial: String,
    val slot: Int,
    val toSlot: Int,
)

/**
 * Moving a multi-disc game's save states, filed under each disc's serial, under the game's key, losing none. Pure: the
 * caller reads the times and runs the moves.
 */
object StateMigration {
    // The resume state, then slots 1 to 10.
    private val PLACES = listOf(SaveSlots.RESUME) + (1..SaveSlots.COUNT)

    private fun LongArray.at(slot: Int): Long = getOrNull(if (slot == SaveSlots.RESUME) 0 else slot) ?: 0L

    private data class State(
        val serial: String,
        val slot: Int,
        val time: Long,
        val order: Int,
    )

    /**
     * The moves that put every disc's states under [key], in the order to run them, the key's own first. [times] is
     * each serial's `getSaveStateTimes`, the key's included, in disc order.
     *
     * Each place (the resume state, slots 1 to 10) goes to its newest state (on a tie, the key's own, then the earlier
     * disc). The others there take the lowest slots no serial uses, the key's own first, then the newest. One left
     * without a slot stays where it is; when it's the key's own, its place's winner stays too.
     */
    fun plan(
        key: String,
        times: Map<String, LongArray>,
    ): List<StateMove> {
        val serials = listOf(key) + times.keys.filter { it != key }
        val byPlace =
            PLACES.associateWith { slot ->
                serials.mapIndexedNotNull { order, serial ->
                    times[serial]?.at(slot)?.takeIf { it > 0 }?.let { State(serial, slot, it, order) }
                }
            }
        val free = PLACES.filter { it != SaveSlots.RESUME && byPlace.getValue(it).isEmpty() }.toMutableList()

        val winners = HashMap<Int, State>()
        val losers = mutableListOf<State>()
        for ((slot, states) in byPlace) {
            val winner =
                states.maxWithOrNull(compareBy<State> { it.time }.thenByDescending { it.order }) ?: continue
            winners[slot] = winner
            losers += states - winner
        }
        losers.sortWith(compareBy<State> { it.serial != key }.thenByDescending { it.time })

        val keyMoves = mutableListOf<StateMove>()
        val otherMoves = mutableListOf<StateMove>()
        for (loser in losers) {
            val to = free.removeFirstOrNull()
            when {
                to != null ->
                    (if (loser.serial == key) keyMoves else otherMoves) +=
                        StateMove(loser.serial, loser.slot, to)
                // The key's own can't make way, so its place stays as it is.
                loser.serial == key -> winners.remove(loser.slot)
            }
        }
        for (slot in PLACES) {
            val winner = winners[slot] ?: continue
            if (winner.serial != key) otherMoves += StateMove(winner.serial, slot, slot)
        }
        return keyMoves + otherMoves.sortedWith(compareBy({ PLACES.indexOf(it.slot) }, { serials.indexOf(it.serial) }))
    }

    /**
     * The key's last slot after [moves]: among the serials whose last slot ([lastSlots]) holds a state, the one whose
     * state is newest, where that state ends up under [key]. Null if there's none, or it stays under another disc.
     */
    fun lastSlot(
        key: String,
        lastSlots: Map<String, Int>,
        times: Map<String, LongArray>,
        moves: List<StateMove>,
    ): Int? {
        val (serial, slot) =
            lastSlots.entries
                .filter { (serial, slot) -> (times[serial]?.at(slot) ?: 0L) > 0 }
                .maxByOrNull { (serial, slot) -> times.getValue(serial).at(slot) }
                ?.toPair() ?: return null
        moves.firstOrNull { it.serial == serial && it.slot == slot }?.let { return it.toSlot }
        return if (serial == key) slot else null
    }
}
