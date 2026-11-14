// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app keeps per game, by its key: the disc last played, and whether its states were migrated. */
class GameRecordsTest {
    private val store = MemoryStore()
    private val values = store.values
    private val records = GameRecords(store)

    @Test
    fun theLastDiscRoundTrips() {
        records.setLastDisc("SLPS-02020", 2)

        assertEquals(2, records.lastDisc("SLPS-02020"))
        assertEquals("2", values["lastDisc.SLPS-02020"])
    }

    @Test
    fun aGameNeverPlayedHasNoLastDisc() {
        assertNull(records.lastDisc("SLPS-02020"))
    }

    @Test
    fun aGarbledLastDiscIsNone() {
        values["lastDisc.SLPS-02020"] = "two"

        assertNull(records.lastDisc("SLPS-02020"))
    }

    @Test
    fun aMigrationIsRecorded() {
        assertFalse(records.migrated("SLPS-02020"))

        records.setMigrated("SLPS-02020")

        assertTrue(records.migrated("SLPS-02020"))
        assertFalse(records.migrated("SLUS-00001"))
        assertEquals("1", values["migrated.SLPS-02020"])
    }
}
