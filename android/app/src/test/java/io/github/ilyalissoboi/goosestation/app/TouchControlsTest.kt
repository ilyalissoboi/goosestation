// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.input.PadKind
import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The touch controls' settings, kept on this device, and when the overlay shows. */
class TouchControlsTest {
    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()

        override fun getString(key: String) = values[key]

        override fun putString(
            key: String,
            value: String?,
        ): Boolean {
            if (value == null) values.remove(key) else values[key] = value
            return true
        }
    }

    private val store = MemoryStore()
    private val posted = mutableListOf<String>()
    private var controllers = 0
    private val touch = TouchControls(TouchPrefs(store), { controllers }) { posted += it }

    private fun shown(
        settings: TouchSettings = TouchSettings(),
        controllers: Int = 0,
        inGame: Boolean = true,
        menuOpen: Boolean = false,
        kind: PadKind? = PadKind.Digital,
    ) = TouchVisibility.of(settings, controllers, inGame, menuOpen, kind)

    @Test
    fun defaults() {
        assertEquals(TouchSettings(TouchMode.Automatic, 100, 100, true), TouchPrefs(store).load())
    }

    @Test
    fun roundTrips() {
        val settings = TouchSettings(TouchMode.Always, 115, 40, false)
        TouchPrefs(store).save(settings)
        assertEquals(settings, TouchPrefs(store).load())
        touch.settings = TouchSettings(mode = TouchMode.Off)
        assertEquals(TouchSettings(mode = TouchMode.Off), TouchPrefs(store).load())
    }

    @Test
    fun badValuesReadAsDefaults() {
        store.values.putAll(mapOf("mode" to "Sometimes", "size" to "big", "opacity" to "5", "vibration" to "maybe"))
        assertEquals(TouchSettings(), TouchPrefs(store).load())
        store.values.putAll(mapOf("size" to "200", "opacity" to "62"))
        val read = TouchPrefs(store).load()
        assertEquals(130, read.size)
        assertEquals(60, read.opacity)
    }

    @Test
    fun offNeverShows() {
        assertNull(shown(TouchSettings(mode = TouchMode.Off)))
    }

    @Test
    fun automaticHidesWithAController() {
        assertEquals(TouchVisibility.Shown(sticks = false), shown(controllers = 0))
        assertNull(shown(controllers = 1))
    }

    @Test
    fun alwaysShowsWithAController() {
        assertEquals(
            TouchVisibility.Shown(sticks = false),
            shown(TouchSettings(mode = TouchMode.Always), controllers = 2),
        )
    }

    @Test
    fun notOverTheMenuNorOutOfAGame() {
        assertNull(shown(menuOpen = true))
        assertNull(shown(inGame = false))
        assertNull(shown(kind = null))
    }

    @Test
    fun noOverlayForOtherTypes() {
        assertNull(shown(kind = PadKind.Unsupported))
    }

    @Test
    fun sticksOnlyInAnalog() {
        assertEquals(TouchVisibility.Shown(sticks = true), shown(kind = PadKind.Analog))
    }

    @Test
    fun theMessageOnceAGame() {
        touch.gameStarted()
        touch.kind = PadKind.Unsupported
        touch.kind = PadKind.Digital
        touch.kind = PadKind.Unsupported
        assertEquals(listOf(MESSAGE), posted)
        touch.gameStarted()
        touch.kind = PadKind.Unsupported
        assertEquals(listOf(MESSAGE, MESSAGE), posted)
    }

    @Test
    fun noMessageWhenOff() {
        touch.settings = TouchSettings(mode = TouchMode.Off)
        touch.gameStarted()
        touch.kind = PadKind.Unsupported
        assertEquals(emptyList<String>(), posted)
    }

    @Test
    fun noMessageWithAControllerInAutomatic() {
        controllers = 1
        touch.gameStarted()
        touch.kind = PadKind.Unsupported
        assertEquals(emptyList<String>(), posted)
        touch.settings = TouchSettings(mode = TouchMode.Always)
        touch.gameStarted()
        touch.kind = PadKind.Unsupported
        assertEquals(listOf(MESSAGE), posted)
    }

    @Test
    fun theOverlayFollowsTheKind() {
        assertNull(touch.shown(inGame = true, menuOpen = false))
        touch.kind = PadKind.Analog
        assertEquals(TouchVisibility.Shown(sticks = true), touch.shown(inGame = true, menuOpen = false))
        controllers = 1
        assertNull(touch.shown(inGame = true, menuOpen = false))
    }

    // Each release (the app paused, the window's focus lost) is a new count, which makes the overlay anew.
    @Test
    fun aReleaseIsANewCount() {
        val before = touch.releases
        touch.release()
        touch.release()
        assertEquals(before + 2, touch.releases)
    }

    private companion object {
        const val MESSAGE = "Touch controls work with a digital pad or an analog controller on Port 1."
    }
}
