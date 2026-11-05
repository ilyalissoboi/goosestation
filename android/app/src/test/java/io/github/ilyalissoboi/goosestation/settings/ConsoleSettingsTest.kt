// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleSettingsTest {
    private fun values(vararg file: Pair<String, String>) =
        SettingValues.read(MemoryCoreSettings().apply { values += file }, ConsoleSettings.all)

    @Test
    fun percentOfAFraction() {
        assertEquals(
            150,
            ConsoleSettings.overclockPercent(
                values(
                    "CPU/OverclockNumerator" to "3",
                    "CPU/OverclockDenominator" to "2",
                ),
            ),
        )
        assertEquals(100, ConsoleSettings.overclockPercent(values()))
    }

    @Test
    fun aFractionDesktopWroteShows() {
        assertEquals(
            233,
            ConsoleSettings.overclockPercent(
                values(
                    "CPU/OverclockNumerator" to "7",
                    "CPU/OverclockDenominator" to "3",
                ),
            ),
        )
    }

    @Test
    fun aPercentIsWrittenAsTheCoresFraction() {
        for ((percent, fraction) in listOf(
            150 to (3 to 2),
            125 to (5 to 4),
            1000 to (10 to 1),
            100 to (1 to 1),
            90 to (9 to 10),
        )) {
            val changed = ConsoleSettings.withOverclockPercent(values(), percent)
            assertEquals(
                "$percent%",
                fraction,
                changed[ConsoleSettings.OVERCLOCK_NUMERATOR] to changed[ConsoleSettings.OVERCLOCK_DENOMINATOR],
            )
        }
    }

    @Test
    fun hardcoreNeedsASignIn() {
        assertFalse(Hardcore.on(values("Cheevos/ChallengeMode" to "true")))
        assertTrue(Hardcore.on(values("Cheevos/ChallengeMode" to "true", "Cheevos/Enabled" to "true")))
    }

    @Test
    fun readSpeedUpsSayTheDriveSpeed() {
        assertEquals("None (double speed)", ConsoleSettings.READ_SPEEDS.label(1))
        assertEquals("4x (8x speed)", ConsoleSettings.READ_SPEEDS.label(4))
        assertEquals(null, ConsoleSettings.READ_SPEEDS.next(10, 1))
    }

    @Test
    fun seekSpeedUpsGoDownToInstant() {
        assertEquals(0, ConsoleSettings.SEEK_SPEEDS.next(1, -1))
        assertEquals("Instant", ConsoleSettings.SEEK_SPEEDS.label(0))
        assertEquals("None (normal speed)", ConsoleSettings.SEEK_SPEEDS.label(1))
        assertEquals("3x", ConsoleSettings.SEEK_SPEEDS.label(3))
    }

    @Test
    fun readaheadIsOffAtZero() {
        assertEquals("Off", ConsoleSettings.READAHEAD.label(0))
        assertEquals("8 sectors", ConsoleSettings.READAHEAD.label(8))
    }
}
