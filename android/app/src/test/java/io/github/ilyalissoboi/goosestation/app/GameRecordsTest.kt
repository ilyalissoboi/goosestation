// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the app keeps per game, by its key: the disc last played. */
class GameRecordsTest {
    private val values = HashMap<String, String>()
    private val store =
        object : KeyValueStore {
            override fun getString(key: String): String? = values[key]

            override fun putString(
                key: String,
                value: String?,
            ): Boolean {
                if (value == null) values.remove(key) else values[key] = value
                return true
            }
        }
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
}
