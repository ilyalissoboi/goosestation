// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSettingsTest {
    @Test
    fun shaderCompileThreadsIsAutomaticByDefault() {
        val values = SettingValues.read(MemoryCoreSettings(), AdvancedSettings.all)

        assertEquals("GPU", AdvancedSettings.SHADER_COMPILE_THREADS.section)
        assertEquals("ShaderCompileThreads", AdvancedSettings.SHADER_COMPILE_THREADS.key)
        assertEquals(0, values[AdvancedSettings.SHADER_COMPILE_THREADS])
    }

    @Test
    fun shaderCompileThreadsIsTheDevicesNotAGames() {
        assertTrue(AdvancedSettings.SHADER_COMPILE_THREADS in AdvancedSettings.all)
        assertFalse(AdvancedSettings.SHADER_COMPILE_THREADS in AdvancedSettings.GAME)
    }

    @Test
    fun theScaleNamesAutomaticWithWhatItGivesAndOneAsBefore() {
        val scale = AdvancedSettings.compileThreadsScale(6)

        assertEquals("Automatic (6)", scale.label(0))
        assertEquals("1 (one at a time)", scale.label(1))
        assertEquals("8", scale.label(8))
    }
}
