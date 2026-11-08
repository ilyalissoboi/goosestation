// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.SaveStatePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The save lists' rows, the game a shared slot holds, and what the questions call each save. */
class SaveListsTest {
    private fun times(vararg saved: Pair<Int, Long>) =
        LongArray(SaveSlots.COUNT + 1).also {
            for ((n, t) in saved) {
                it[n] =
                    t
            }
        }

    @Test
    fun sharedSaveListsTenSlots() {
        val entries = SaveLists.shared(SlotMode.Save, times(3 to 5L))
        assertEquals((1..10).toList(), entries.map { it.slot })
        assertTrue(entries.all { it.shared && it.owner == null })
        assertEquals(5L, entries[2].savedAt)
        assertNull(entries[0].savedAt)
    }

    @Test
    fun sharedLoadListsSavedOnes() {
        assertEquals(listOf(SlotEntry(3, 5L, shared = true)), SaveLists.shared(SlotMode.Load, times(3 to 5L)))
        // [0] isn't a slot: shared slots have no resume state.
        assertEquals(emptyList<SlotEntry>(), SaveLists.shared(SlotMode.Load, times(0 to 9L)))
    }

    @Test
    fun heldGameLabelsByLibraryTitleElseSerial() {
        val inLibrary =
            SaveLists.held(
                SaveStatePreview(null, 0, "SCUS-94900", "CRASH", "/g/crash.chd", "Crash Bandicoot"),
            )
        assertEquals(HeldGame("Crash Bandicoot", "SCUS-94900", "/g/crash.chd"), inLibrary)
        assertEquals("Crash Bandicoot", inLibrary!!.label)

        val unknown = SaveLists.held(SaveStatePreview(null, 0, "SCUS-94900", "CRASH", "", ""))
        assertEquals(HeldGame("CRASH", "SCUS-94900", null), unknown)
        assertEquals("SCUS-94900", unknown!!.label)
        assertEquals("CRASH", HeldGame("CRASH", "", null).label)

        assertNull(SaveLists.held(SaveStatePreview(null, 0)))
    }

    @Test
    fun deleteQuestionsSayWhichSave() {
        assertEquals("Delete the save in slot 3?", SaveLists.deleteQuestion(SlotEntry(3, 1L)))
        assertEquals("Delete the save in shared slot 3?", SaveLists.deleteQuestion(SlotEntry(3, 1L, shared = true)))
        assertEquals("Delete your resume state?", SaveLists.deleteQuestion(SlotEntry(SaveSlots.RESUME, 1L)))
        assertEquals(
            "Delete disc 2's save in slot 3?",
            SaveLists.deleteQuestion(SlotEntry(3, 1L, owner = "SLPS-02021", disc = 2)),
        )
        assertEquals(
            "Delete the other disc's save in slot 3?",
            SaveLists.deleteQuestion(SlotEntry(3, 1L, owner = "SLPS-02021")),
        )
        assertEquals(
            "Delete disc 2's resume state?",
            SaveLists.deleteQuestion(SlotEntry(SaveSlots.RESUME, 1L, owner = "SLPS-02021", disc = 2)),
        )
    }

    @Test
    fun aRowsStateIsItsOwnersOrTheGames() {
        assertEquals(StateRef("KEY", 3), SlotEntry(3, 1L).ref("KEY"))
        assertEquals(StateRef("SLPS-02021", 3), SlotEntry(3, 1L, owner = "SLPS-02021").ref("KEY"))
        assertEquals(StateRef("", 3, shared = true), SlotEntry(3, 1L, shared = true).ref("KEY"))
    }
}
