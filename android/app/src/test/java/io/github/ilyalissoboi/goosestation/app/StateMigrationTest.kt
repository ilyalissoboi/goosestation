// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Moving each disc's save states under the game's key, losing none. */
class StateMigrationTest {
    // getSaveStateTimes' form: [0] the resume state, [n] slot n.
    private fun times(vararg saved: Pair<Int, Long>): LongArray {
        val times = LongArray(SaveSlots.COUNT + 1)
        for ((slot, time) in saved) times[if (slot == SaveSlots.RESUME) 0 else slot] = time
        return times
    }

    private fun move(
        serial: String,
        slot: Int,
        toSlot: Int,
    ) = StateMove(serial, slot, toSlot)

    // The times once the moves have run, as the core would list them.
    private fun after(
        times: Map<String, LongArray>,
        moves: List<StateMove>,
    ): Map<String, LongArray> {
        val result = times.mapValues { it.value.copyOf() }

        fun index(slot: Int) = if (slot == SaveSlots.RESUME) 0 else slot
        for (move in moves) {
            val time = result.getValue(move.serial)[index(move.slot)]
            check(time > 0 && result.getValue(KEY)[index(move.toSlot)] == 0L) { "bad move $move" }
            result.getValue(move.serial)[index(move.slot)] = 0
            result.getValue(KEY)[index(move.toSlot)] = time
        }
        return result
    }

    @Test
    fun aLoneDiscsStatesMoveToTheirPlaces() {
        val times = mapOf(KEY to times(), DISC2 to times(SaveSlots.RESUME to 100L, 2 to 200L))

        assertEquals(
            listOf(move(DISC2, SaveSlots.RESUME, SaveSlots.RESUME), move(DISC2, 2, 2)),
            StateMigration.plan(KEY, times),
        )
    }

    @Test
    fun theNewestKeepsThePlace() {
        val times = mapOf(KEY to times(3 to 100L), DISC2 to times(3 to 200L))

        assertEquals(listOf(move(KEY, 3, 1), move(DISC2, 3, 3)), StateMigration.plan(KEY, times))
    }

    @Test
    fun theKeysOwnLoserMovesFirst() {
        val times = mapOf(KEY to times(5 to 100L), DISC2 to times(2 to 50L, 5 to 200L))

        val moves = StateMigration.plan(KEY, times)

        assertEquals(move(KEY, 5, 1), moves.first())
        assertEquals(setOf(move(KEY, 5, 1), move(DISC2, 2, 2), move(DISC2, 5, 5)), moves.toSet())
    }

    @Test
    fun aTieGoesToTheKey() {
        val times = mapOf(KEY to times(3 to 100L), DISC2 to times(3 to 100L))

        assertEquals(listOf(move(DISC2, 3, 1)), StateMigration.plan(KEY, times))
    }

    @Test
    fun twoResumeStates() {
        val times = mapOf(KEY to times(SaveSlots.RESUME to 100L), DISC2 to times(SaveSlots.RESUME to 200L))

        assertEquals(
            listOf(move(KEY, SaveSlots.RESUME, 1), move(DISC2, SaveSlots.RESUME, SaveSlots.RESUME)),
            StateMigration.plan(KEY, times),
        )
    }

    @Test
    fun fullSlotsLeaveTheRestInPlace() {
        val times =
            mapOf(
                KEY to times(*(1..10).map { it to it * 10L }.toTypedArray()),
                DISC2 to times(1 to 1000L, 2 to 1000L, 3 to 1000L),
            )

        assertEquals(emptyList<StateMove>(), StateMigration.plan(KEY, times))
    }

    @Test
    fun fullSlotsMoveWhatFits() {
        val times =
            mapOf(
                KEY to times(*(1..9).map { it to it * 10L }.toTypedArray()),
                DISC2 to times(1 to 1000L, 2 to 1000L),
            )

        // The key's own losers in slots 1 and 2: the newer (slot 2) takes slot 10, and slot 1 stays as it is.
        assertEquals(listOf(move(KEY, 2, 10), move(DISC2, 2, 2)), StateMigration.plan(KEY, times))
    }

    @Test
    fun threeDiscsShareTheFreeSlots() {
        val times =
            mapOf(
                KEY to times(1 to 100L),
                DISC2 to times(1 to 300L),
                DISC3 to times(1 to 200L),
            )

        val moves = StateMigration.plan(KEY, times)

        assertEquals(setOf(move(KEY, 1, 2), move(DISC2, 1, 1), move(DISC3, 1, 3)), moves.toSet())
        assertEquals(move(KEY, 1, 2), moves.first())
    }

    @Test
    fun aSecondPlanFindsNothing() {
        val times =
            mapOf(
                KEY to times(SaveSlots.RESUME to 100L, 3 to 100L, 4 to 400L),
                DISC2 to times(SaveSlots.RESUME to 200L, 3 to 300L, 5 to 50L),
            )
        val moves = StateMigration.plan(KEY, times)

        assertEquals(emptyList<StateMove>(), StateMigration.plan(KEY, after(times, moves)))
    }

    @Test
    fun theLastSlotFollowsItsState() {
        val times = mapOf(KEY to times(3 to 100L), DISC2 to times(3 to 200L))
        val moves = StateMigration.plan(KEY, times)

        // Disc 2's slot 3 is the newest of the last slots, and keeps its place.
        assertEquals(3, StateMigration.lastSlot(KEY, mapOf(KEY to 3, DISC2 to 3), times, moves))
        // The key's own slot 3 moved to slot 1.
        assertEquals(1, StateMigration.lastSlot(KEY, mapOf(KEY to 3), times, moves))
    }

    @Test
    fun aLastSlotLeftUnderItsDiscIsNone() {
        val times =
            mapOf(
                KEY to times(*(1..10).map { it to it * 10L }.toTypedArray()),
                DISC2 to times(1 to 1000L),
            )
        val moves = StateMigration.plan(KEY, times)

        assertNull(StateMigration.lastSlot(KEY, mapOf(DISC2 to 1), times, moves))
    }

    private companion object {
        const val KEY = "SLPS-02020"
        const val DISC2 = "SLPS-02021"
        const val DISC3 = "SLPS-02022"
    }
}
