// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmulationSettingsTest {
    private fun values(vararg file: Pair<String, String>) =
        SettingValues.read(MemoryCoreSettings().apply { values += file }, EmulationSettings.all)

    @Test
    fun speedsAreStoredAsFactors() {
        assertEquals(1.25f, EmulationSettings.factor(125))
        assertEquals(133, EmulationSettings.percent(1.33f))
        assertEquals(100, EmulationSettings.percent(values()[EmulationSettings.EMULATION_SPEED]))
        assertEquals(0, EmulationSettings.percent(values()[EmulationSettings.TURBO_SPEED]))
    }

    @Test
    fun speedsWalkDesktopsLadder() {
        assertEquals("Unlimited", EmulationSettings.SPEEDS.label(0))
        assertEquals("150%", EmulationSettings.SPEEDS.label(150))
        assertEquals(125, EmulationSettings.SPEEDS.next(100, 1))
        assertEquals(90, EmulationSettings.SPEEDS.next(100, -1))
        assertEquals(600, EmulationSettings.SPEEDS.next(500, 1))
        assertNull(EmulationSettings.SPEEDS.next(1000, 1))
        assertEquals(150, EmulationSettings.SPEEDS.next(133, 1))
    }

    @Test
    fun belowAHundredIsNotedInHardcoreMode() {
        assertEquals("Runs at 100% in hardcore mode.", EmulationSettings.speedNote(90, hardcore = true))
        assertNull(EmulationSettings.speedNote(0, hardcore = true))
        assertNull(EmulationSettings.speedNote(100, hardcore = true))
        assertNull(EmulationSettings.speedNote(90, hardcore = false))
    }

    @Test
    fun rewindFrequencyIsInHundredths() {
        assertEquals("Every frame", EmulationSettings.REWIND_FREQUENCIES.label(0))
        assertEquals("0.05 s", EmulationSettings.REWIND_FREQUENCIES.label(5))
        assertEquals("10 s", EmulationSettings.REWIND_FREQUENCIES.label(1000))
        assertEquals(1000, EmulationSettings.hundredths(values()[EmulationSettings.REWIND_FREQUENCY]))
    }

    @Test
    fun theRewindNoteSaysHowFarBackAndWhatItTakes() {
        val usage = RewindUsage(40L * 1_048_576, 20L * 1_048_576)

        assertEquals(
            "Goes back up to 100.0 s, using up to 40 MB of memory and 20 MB of video memory.",
            EmulationSettings.rewindNote(10f, 10, usage),
        )
        // Every frame is a 60th of a second.
        assertEquals(
            "Goes back up to 0.2 s, using up to 40 MB of memory and 20 MB of video memory.",
            EmulationSettings.rewindNote(0f, 10, usage),
        )
    }

    @Test
    fun theFrameTimeBufferIsInTenths() {
        assertEquals(20, EmulationSettings.tenths(values()[EmulationSettings.FRAME_TIME_BUFFER]))
        assertEquals("2.0 ms", EmulationSettings.FRAME_TIME_BUFFERS.label(20))
        assertEquals(30, EmulationSettings.FRAME_TIME_BUFFERS.next(20, 1))
    }

    @Test
    fun runAheadCountsFrames() {
        assertEquals("Off", EmulationSettings.RUNAHEAD_FRAMES.label(0))
        assertEquals("1 frame", EmulationSettings.RUNAHEAD_FRAMES.label(1))
        assertEquals("3 frames", EmulationSettings.RUNAHEAD_FRAMES.label(3))
    }
}
