// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The slot last saved to or loaded from, per game, in a key-value store. */
class LastSlotsTest {
    private val store = MemoryStore()
    private val values = store.values
    private val lastSlots = LastSlots(store)

    @Test
    fun itRoundTripsPerGame() {
        lastSlots.set("SCUS-94900", 3)
        lastSlots.set("SLUS-00001", SaveSlots.RESUME)

        assertEquals(3, lastSlots.get("SCUS-94900"))
        assertEquals(SaveSlots.RESUME, lastSlots.get("SLUS-00001"))
        assertEquals("3", values["lastSlot.SCUS-94900"])
    }

    @Test
    fun aGameNeverSavedHasNone() {
        assertNull(lastSlots.get("SCUS-94900"))
    }

    @Test
    fun aGarbledValueIsNone() {
        values["lastSlot.SCUS-94900"] = "three"

        assertNull(lastSlots.get("SCUS-94900"))
    }
}
