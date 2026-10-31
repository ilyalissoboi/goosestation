// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** When a state was saved, as the slot list says it: Today, Yesterday, or the date, then the 24-hour time. */
class SaveTimesTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val now = ZonedDateTime.of(2026, 10, 31, 14, 5, 0, 0, zone)

    private fun at(
        day: Int,
        hour: Int,
        minute: Int,
        month: Int = 10,
    ) = ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toEpochSecond()

    @Test
    fun today() {
        assertEquals("Today · 09:07", SaveTimes.label(at(31, 9, 7), now))
    }

    @Test
    fun yesterday() {
        assertEquals("Yesterday · 23:59", SaveTimes.label(at(30, 23, 59), now))
    }

    @Test
    fun older() {
        assertEquals("28 Oct 2026 · 21:17", SaveTimes.label(at(28, 21, 17), now))
        assertEquals("1 Sep 2026 · 08:00", SaveTimes.label(at(1, 8, 0, month = 9), now))
    }

    @Test
    fun justAfterMidnightYesterdayIsYesterday() {
        val midnight = ZonedDateTime.of(2026, 10, 31, 0, 1, 0, 0, zone)
        assertEquals("Yesterday · 23:58", SaveTimes.label(at(30, 23, 58), midnight))
        assertEquals("Today · 00:00", SaveTimes.label(at(31, 0, 0), midnight))
    }
}
