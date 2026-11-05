// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingValuesTest {
    private val speed = FloatSetting("Main", "EmulationSpeed", 1f)
    private val paused = BoolSetting("Main", "StartPaused", false)
    private val slots = IntSetting("Main", "RewindSaveSlots", 10)
    private val renderer = NameSetting("GPU", "Renderer", "Automatic", NameList.GPURenderer)
    private val page = listOf(speed, paused, slots, renderer)

    @Test
    fun readsEachSettingOrItsDefault() {
        val store = MemoryCoreSettings().apply { values["Main/EmulationSpeed"] = "1.5" }

        val values = SettingValues.read(store, page)

        assertEquals(1.5f, values[speed])
        assertEquals(false, values[paused])
        assertEquals(10, values[slots])
        assertEquals("Automatic", values[renderer])
    }

    @Test
    fun savesOnlyWhatChanged() {
        val store = MemoryCoreSettings()
        val old = SettingValues.read(store, page)

        old.with(speed, 2f).save(store, old)

        assertEquals(mapOf("Main/EmulationSpeed" to "2.0"), store.values)
    }

    @Test
    fun twoChangesSaveBoth() {
        val old = SettingValues.read(MemoryCoreSettings(), page)

        val new = old.with(slots, 20).with(speed, 3f)

        assertEquals(listOf(speed, slots), new.changed(old))
    }

    @Test
    fun aValueSetBackIsNoChange() {
        val old = SettingValues.read(MemoryCoreSettings(), page)

        assertEquals(old, old.with(slots, 20).with(slots, 10))
        assertTrue(
            old
                .with(slots, 20)
                .with(slots, 10)
                .changed(old)
                .isEmpty(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun anUnreadSettingThrows() {
        SettingValues.read(MemoryCoreSettings(), listOf(speed))[slots]
    }

    @Test
    fun aNameSettingKeepsAnUnknownName() {
        val store = MemoryCoreSettings().apply { values["GPU/Renderer"] = "Metal" }

        val values = SettingValues.read(store, page)

        assertEquals("Metal", values[renderer])
        assertEquals(mapOf("GPU/Renderer" to "Metal"), store.values)
    }
}
