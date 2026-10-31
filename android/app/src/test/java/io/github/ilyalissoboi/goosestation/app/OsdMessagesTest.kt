// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The messages shown over the game, with a clock the test moves. */
class OsdMessagesTest {
    private var now = 0L
    private val osd = OsdMessages { now }

    private fun texts() = osd.shown.map { it.text }

    @Test
    fun aMessageLastsItsSeconds() {
        osd.post("Saved to slot 1")

        now = 2_999
        osd.expire()
        assertEquals(listOf("Saved to slot 1"), texts())

        now = 3_000
        osd.expire()
        assertEquals(emptyList<String>(), texts())
    }

    @Test
    fun aKeyReplacesInPlace() {
        osd.post("a", key = "k")
        now = 500
        osd.post("b")
        now = 1_000
        osd.post("c", key = "k", seconds = 5f)

        assertEquals(listOf("c", "b"), texts())
        assertEquals(6_000L, osd.shown.first().untilMs)
    }

    @Test
    fun removeDropsByKey() {
        osd.post("a", key = "k")
        osd.post("b")

        osd.remove("k")

        assertEquals(listOf("b"), texts())
    }

    @Test
    fun aFifthDropsTheOldest() {
        for (text in listOf("1", "2", "3", "4", "5")) osd.post(text)

        assertEquals(listOf("2", "3", "4", "5"), texts())
    }

    @Test
    fun nextExpiryIsTheSoonest() {
        assertNull(osd.nextExpiry())

        osd.post("long", seconds = 10f)
        now = 1_000
        osd.post("short")

        assertEquals(4_000L, osd.nextExpiry())
    }

    @Test
    fun clearDropsEverything() {
        osd.post("a")
        osd.post("b", key = "k")

        osd.clear()

        assertEquals(emptyList<String>(), texts())
        assertNull(osd.nextExpiry())
    }
}
