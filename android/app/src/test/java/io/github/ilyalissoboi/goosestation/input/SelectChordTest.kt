// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import io.github.ilyalissoboi.goosestation.input.MenuTrigger.Companion.SELECT
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.KeyPress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Select held back on a pad with a hotkey chord: sent on its release, with the next button, or after 500 ms. */
class SelectChordTest {
    private companion object {
        const val PAD = 1
        const val OTHER_PAD = 2
        const val BUTTON_X = 99
    }

    private val chord = SelectChord()
    private val notPaired: (Int) -> Boolean = { false }

    private fun down(keyCode: Int) = KeyPress(keyCode, pressed = true)

    private fun up(keyCode: Int) = KeyPress(keyCode, pressed = false)

    private fun plan(
        press: KeyPress,
        nowMs: Long,
        chords: Boolean = true,
        canceled: Boolean = false,
        deviceId: Int = PAD,
    ) = chord.plan(deviceId, listOf(press), chords, canceled, nowMs)

    @Test
    fun withoutChordsSelectGoesAtOnce() {
        assertEquals(listOf(down(SELECT)), plan(down(SELECT), 0, chords = false))
        assertNull(chord.deadline(notPaired))
    }

    @Test
    fun aTapGoesOnRelease() {
        assertEquals(emptyList<KeyPress>(), plan(down(SELECT), 0))

        assertEquals(listOf(down(SELECT), up(SELECT)), plan(up(SELECT), 80))
        assertNull(chord.deadline(notPaired))
    }

    @Test
    fun aHeldSelectGoesAfter500ms() {
        plan(down(SELECT), 0)

        assertEquals(500L, chord.deadline(notPaired))
        assertEquals(emptyList<Int>(), chord.onTime(499, notPaired))
        assertEquals(listOf(PAD), chord.onTime(500, notPaired))
        assertNull(chord.deadline(notPaired))
        // Sent already: its release goes as it comes.
        assertEquals(listOf(up(SELECT)), plan(up(SELECT), 900))
    }

    @Test
    fun aButtonGoesWithSelect() {
        plan(down(SELECT), 0)

        assertEquals(listOf(down(SELECT), down(BUTTON_X)), plan(down(BUTTON_X), 120))
        assertNull(chord.deadline(notPaired))
        assertEquals(listOf(up(BUTTON_X)), plan(up(BUTTON_X), 200))
        assertEquals(listOf(up(SELECT)), plan(up(SELECT), 260))
    }

    @Test
    fun aButtonsReleaseDoesntSendSelect() {
        plan(down(SELECT), 0)

        assertEquals(listOf(up(BUTTON_X)), plan(up(BUTTON_X), 50))
        assertEquals(500L, chord.deadline(notPaired))
    }

    @Test
    fun aCancelledSelectIsDropped() {
        plan(down(SELECT), 0)

        assertEquals(emptyList<KeyPress>(), plan(up(SELECT), 100, canceled = true))
        assertNull(chord.deadline(notPaired))
    }

    @Test
    fun noTimeoutWhilePaired() {
        plan(down(SELECT), 0)

        assertEquals(emptyList<Int>(), chord.onTime(600) { true })
        assertNull(chord.deadline { true })
        // Once the pair has ended without the menu, the time that's up counts.
        assertEquals(listOf(PAD), chord.onTime(600, notPaired))
    }

    @Test
    fun devicesAreSeparate() {
        plan(down(SELECT), 0)
        plan(down(SELECT), 300, deviceId = OTHER_PAD)

        assertEquals(listOf(PAD), chord.onTime(500, notPaired))
        assertEquals(800L, chord.deadline(notPaired))
        assertEquals(listOf(down(BUTTON_X)), plan(down(BUTTON_X), 600))
    }

    @Test
    fun resetDropsEveryHold() {
        plan(down(SELECT), 0)
        plan(down(SELECT), 0, deviceId = OTHER_PAD)

        chord.reset()

        assertNull(chord.deadline(notPaired))
        assertEquals(emptyList<Int>(), chord.onTime(1_000, notPaired))
    }

    @Test
    fun forgetDropsADevicesHold() {
        plan(down(SELECT), 0)

        chord.forget(PAD)

        assertNull(chord.deadline(notPaired))
    }

    @Test
    fun aLateTapFromTheMenuTriggerIsHeldDownAsOne() {
        // MenuTrigger sends a Select it held back as down then up in one event.
        assertEquals(
            listOf(down(SELECT), up(SELECT)),
            chord.plan(PAD, listOf(down(SELECT), up(SELECT)), true, false, 300),
        )
    }
}
