// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import io.github.ilyalissoboi.goosestation.input.LateRelease.Release
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.Companion.SELECT
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.Companion.START
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.KeyPress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A press sent late, all at once, is held down long enough for the game to see it, and never lands on a later one. */
class LateReleaseTest {
    private companion object {
        const val PAD = 1
        const val OTHER_PAD = 2
        const val BUTTON_A = 96
    }

    private val trigger = MenuTrigger()
    private val late = LateRelease()

    private fun down(keyCode: Int) = KeyPress(keyCode, pressed = true)

    private fun up(keyCode: Int) = KeyPress(keyCode, pressed = false)

    // What the core is sent now for a key event, as ControllerInput does it.
    private fun key(
        keyCode: Int,
        pressed: Boolean,
        nowMs: Long,
        deviceId: Int = PAD,
    ): List<KeyPress> = late.plan(deviceId, trigger.onKey(deviceId, keyCode, pressed, nowMs).send, nowMs)

    // Select held, Start tapped: the late Start comes at its key-up.
    private fun tapStartWithSelectHeld() {
        assertEquals(listOf(down(SELECT)), key(SELECT, true, 0))
        assertEquals(emptyList<KeyPress>(), key(START, true, 100))
        assertEquals(listOf(down(START)), key(START, false, 160))
    }

    @Test
    fun aLateTapIsReleased100msLater() {
        tapStartWithSelectHeld()

        assertEquals(260L, late.deadline())
        assertEquals(emptyList<Release>(), late.due(259))
        assertEquals(listOf(Release(PAD, START)), late.due(260))
        assertNull(late.deadline())
        assertEquals(emptyList<Release>(), late.due(1000))
    }

    @Test
    fun anotherEventForTheKeyReleasesItFirst() {
        tapStartWithSelectHeld()

        // Select let go at 180, which leaves Start's key-up waiting. A second Start press at 200: the waiting key-up
        // goes first, then the press itself.
        assertEquals(listOf(up(SELECT)), key(SELECT, false, 180))
        assertEquals(260L, late.deadline())
        assertTrue(late.flush(PAD, START))
        assertEquals(listOf(down(START)), key(START, true, 200))
        assertNull(late.deadline())
        assertFalse(late.flush(PAD, START))
    }

    @Test
    fun releaseAllReleasesIt() {
        tapStartWithSelectHeld()

        assertEquals(listOf(Release(PAD, START)), late.flushAll())
        assertNull(late.deadline())
        assertEquals(emptyList<Release>(), late.flushAll())
    }

    @Test
    fun aDisconnectReleasesThatPadsOnly() {
        tapStartWithSelectHeld()
        key(SELECT, true, 0, OTHER_PAD)
        key(START, true, 120, OTHER_PAD)
        key(START, false, 180, OTHER_PAD)

        assertEquals(listOf(START), late.flushDevice(PAD))
        assertEquals(280L, late.deadline())
    }

    @Test
    fun otherKeysAndPadsDontReleaseIt() {
        tapStartWithSelectHeld()

        assertFalse(late.flush(PAD, SELECT))
        assertFalse(late.flush(OTHER_PAD, START))
        assertEquals(260L, late.deadline())
    }

    @Test
    fun everythingElseIsSentAsItComes() {
        assertEquals(listOf(down(BUTTON_A)), key(BUTTON_A, true, 0))
        assertEquals(listOf(up(BUTTON_A)), key(BUTTON_A, false, 10))
        // The first let go: the one held back is pressed late, and released on its own key-up.
        key(SELECT, true, 0)
        key(START, true, 100)
        assertEquals(listOf(up(SELECT), down(START)), key(SELECT, false, 300))
        assertEquals(listOf(up(START)), key(START, false, 400))
        assertNull(late.deadline())
    }
}
