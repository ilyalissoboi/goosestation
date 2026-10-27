// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayTimeTest {
    @Test
    fun zeroIsNeverPlayed() {
        assertEquals("Never played", PlayTime.format(0))
    }

    @Test
    fun underAMinute() {
        assertEquals("Less than a minute", PlayTime.format(59))
    }

    @Test
    fun anHourShowsItsMinutes() {
        assertEquals("1h 0m", PlayTime.format(3600))
    }

    @Test
    fun hoursAndMinutes() {
        assertEquals("1h 30m", PlayTime.format(5400))
    }

    @Test
    fun minutesAlone() {
        assertEquals("10m", PlayTime.format(600))
    }
}
