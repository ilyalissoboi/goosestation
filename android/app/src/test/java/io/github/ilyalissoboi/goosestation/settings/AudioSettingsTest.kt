// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioSettingsTest {
    @Test
    fun theVolumesRange() {
        assertNull(AudioSettings.OUTPUT_VOLUMES.next(200, 1))
        assertNull(AudioSettings.FAST_FORWARD_VOLUMES.next(100, 1))
        assertEquals("50%", AudioSettings.FAST_FORWARD_VOLUMES.label(50))
    }

    @Test
    fun theLatenciesAreInMilliseconds() {
        assertEquals("100 ms", AudioSettings.BUFFER_SIZES.label(100))
        assertEquals(110, AudioSettings.BUFFER_SIZES.next(100, 1))
        assertEquals(21, AudioSettings.OUTPUT_LATENCIES.next(20, 1))
    }

    @Test
    fun androidsDefaults() {
        val values = SettingValues.read(MemoryCoreSettings(), AudioSettings.all)
        assertEquals(100, values[AudioSettings.BUFFER_MS])
        assertEquals(100, values[AudioSettings.FAST_FORWARD_VOLUME])
    }
}
