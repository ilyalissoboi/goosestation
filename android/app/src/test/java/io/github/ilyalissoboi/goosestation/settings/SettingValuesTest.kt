// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    // --- A game's values, over the global ones ---

    private val resolution = IntSetting("GPU", "ResolutionScale", 1)
    private val gamePage = listOf(resolution, speed, paused)

    private fun gameValues(
        global: MemoryCoreSettings = MemoryCoreSettings().apply { values["GPU/ResolutionScale"] = "3" },
        game: MemoryCoreSettings = MemoryCoreSettings(),
    ) = SettingValues.readGame(global, game, gamePage)

    @Test
    fun aGamesValueOverridesTheGlobalOne() {
        val values = gameValues(game = MemoryCoreSettings().apply { values["GPU/ResolutionScale"] = "5" })

        assertTrue(values.isGame)
        assertEquals(5, values[resolution])
        assertEquals(3, values.global(resolution))
        assertTrue(values.isSet(resolution))
    }

    @Test
    fun unsetFollowsTheGlobalOne() {
        val values = gameValues()

        assertEquals(3, values[resolution])
        assertFalse(values.isSet(resolution))
        assertEquals(1f, values[speed])
    }

    // Unlike Big Picture, which deletes a value set to the default: it's the game's own, and stays when global moves.
    @Test
    fun aGlobalEqualValueIsStillSet() {
        val game = MemoryCoreSettings()
        val old = gameValues(game = game)

        val new = old.with(resolution, 3)

        assertTrue(new.isSet(resolution))
        assertEquals(listOf(resolution), new.changed(old))
        assertTrue(new != old)
        new.save(game, old)
        assertEquals(mapOf("GPU/ResolutionScale" to "3"), game.values)
    }

    @Test
    fun useGlobalDeletes() {
        val global = MemoryCoreSettings().apply { values["GPU/ResolutionScale"] = "3" }
        val game = MemoryCoreSettings().apply { values["GPU/ResolutionScale"] = "5" }
        val old = gameValues(global, game)

        val new = old.withGlobal(resolution)

        assertEquals(3, new[resolution])
        assertFalse(new.isSet(resolution))
        new.save(game, old)
        assertTrue(game.values.isEmpty())
        assertEquals(mapOf("GPU/ResolutionScale" to "3"), global.values)
    }

    @Test
    fun settingsValuesHaveNoLayer() {
        val values = SettingValues.read(MemoryCoreSettings(), page)

        assertFalse(values.isGame)
        assertFalse(values.isSet(speed))
        assertEquals(1f, values.global(speed))
        assertThrows(IllegalStateException::class.java) { values.withGlobal(speed) }
    }

    @Test
    fun setCountCountsOnlyTheGivenOnes() {
        val values =
            gameValues(
                game =
                    MemoryCoreSettings().apply {
                        values["GPU/ResolutionScale"] = "5"
                        values["Main/EmulationSpeed"] = "2.0"
                    },
            )

        assertEquals(2, values.setCount(gamePage))
        assertEquals(1, values.setCount(listOf(resolution, paused)))
    }

    @Test
    fun aGameSaveWritesOnlyWhatChanged() {
        val game = MemoryCoreSettings().apply { values["Main/EmulationSpeed"] = "2.0" }
        val old = gameValues(game = game)

        old.with(paused, true).save(game, old)

        assertEquals(mapOf("Main/EmulationSpeed" to "2.0", "Main/StartPaused" to "true"), game.values)
    }
}
