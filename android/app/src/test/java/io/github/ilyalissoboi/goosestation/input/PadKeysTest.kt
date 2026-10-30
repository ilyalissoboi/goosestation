// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import io.github.ilyalissoboi.goosestation.input.LateRelease.Release
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.Companion.KEYCODE_BUTTON_MODE
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.Companion.SELECT
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.Companion.START
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.KeyPress
import io.github.ilyalissoboi.goosestation.input.PadKeys.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** ControllerInput's key decisions: where each key event goes, and what the core is sent for it. */
class PadKeysTest {
    private companion object {
        const val PAD = 1
        const val OTHER_PAD = 2
        const val BUTTON_A = 96
    }

    private val keys = PadKeys()

    private fun down(keyCode: Int) = KeyPress(keyCode, pressed = true)

    private fun up(keyCode: Int) = KeyPress(keyCode, pressed = false)

    private fun core(vararg send: KeyPress) = Outcome(toCore = true, send = send.toList(), openMenu = false)

    private val compose = Outcome(toCore = false, send = emptyList(), openMenu = false)

    // A key event as ControllerInput passes it: its event time, and now, when it's handled.
    private fun key(
        keyCode: Int,
        pressed: Boolean,
        eventMs: Long,
        nowMs: Long = eventMs,
        toCore: Boolean = true,
        canceled: Boolean = false,
        repeat: Boolean = false,
        deviceId: Int = PAD,
    ): Outcome = keys.onKey(deviceId, keyCode, pressed, repeat, canceled, toCore, eventMs, nowMs)

    private fun holdStartWithSelect() {
        assertEquals(core(down(SELECT)), key(SELECT, true, 0))
        assertEquals(core(), key(START, true, 100))
    }

    @Test
    fun aKeyGoesToTheCoreOrToCompose() {
        assertEquals(core(down(BUTTON_A)), key(BUTTON_A, true, 0))
        assertEquals(core(), key(BUTTON_A, true, 10, repeat = true, toCore = false))
        assertEquals(core(up(BUTTON_A)), key(BUTTON_A, false, 20, toCore = false))
        assertEquals(compose, key(BUTTON_A, true, 30, toCore = false))
        assertEquals(compose, key(BUTTON_A, false, 40, toCore = true))
    }

    @Test
    fun modeOpensTheMenuAndIsNeverSent() {
        assertEquals(Outcome(toCore = true, send = emptyList(), openMenu = true), key(KEYCODE_BUTTON_MODE, true, 0))
        assertEquals(core(), key(KEYCODE_BUTTON_MODE, false, 50, toCore = false))
    }

    @Test
    fun aCancelledHeldBackPressSendsNothing() {
        holdStartWithSelect()

        assertEquals(core(), key(START, false, 300, canceled = true))
        assertNull(keys.deadline())
        // Select was sent, so its cancelled key-up still goes.
        assertEquals(core(up(SELECT)), key(SELECT, false, 310, canceled = true))
        assertNull(keys.deadline())
    }

    @Test
    fun aCancelledSelectDropsTheStartHeldBack() {
        holdStartWithSelect()

        assertEquals(core(up(SELECT)), key(SELECT, false, 300, canceled = true))
        assertNull(keys.deadline())
        // Start never reached the core: the rest of it is Compose's.
        assertEquals(compose, key(START, false, 310, canceled = true, toCore = false))
    }

    @Test
    fun aCancelledKeyUpOfASentPressIsSent() {
        key(BUTTON_A, true, 0)

        assertEquals(core(up(BUTTON_A)), key(BUTTON_A, false, 10, canceled = true))
    }

    @Test
    fun aLateTapIsReleasedFromWhenItsSentNotFromItsEvent() {
        holdStartWithSelect()

        // Its key-up happened at 160, but the main thread got to it at 300.
        assertEquals(core(down(START)), key(START, false, eventMs = 160, nowMs = 300))
        assertEquals(400L, keys.deadline())
        assertEquals(PadKeys.Tick(emptyList(), openMenu = false), keys.onTime(399))
        assertEquals(PadKeys.Tick(listOf(Release(PAD, START)), openMenu = false), keys.onTime(400))
        assertNull(keys.deadline())
    }

    @Test
    fun theHoldIsTimedFromTheEvents() {
        key(SELECT, true, eventMs = 0, nowMs = 50)
        key(START, true, eventMs = 100, nowMs = 400)

        assertEquals(1100L, keys.deadline())
        assertEquals(PadKeys.Tick(emptyList(), openMenu = true), keys.onTime(1100))
        // The press held back was dropped: its key-up is Compose's.
        assertEquals(compose, key(START, false, 1200, toCore = false))
        assertEquals(core(up(SELECT)), key(SELECT, false, 1210, toCore = false))
    }

    @Test
    fun anotherEventForALateTapsKeyReleasesItFirst() {
        holdStartWithSelect()
        key(START, false, 160)
        key(SELECT, false, 180)

        assertEquals(core(up(START), down(START)), key(START, true, 200))
        assertNull(keys.deadline())
    }

    @Test
    fun releaseAllLetsGoOfLateTapsAndDropsHeldBackPresses() {
        holdStartWithSelect()
        key(SELECT, true, 0, deviceId = OTHER_PAD)
        key(START, true, 100, deviceId = OTHER_PAD)
        key(START, false, 160, deviceId = OTHER_PAD)

        assertEquals(listOf(Release(OTHER_PAD, START)), keys.releaseAll())
        assertNull(keys.deadline())
        assertEquals(compose, key(START, false, 300, toCore = false))
    }

    @Test
    fun aGonePadsHoldAndLateTapsEnd() {
        holdStartWithSelect()
        key(SELECT, true, 0, deviceId = OTHER_PAD)
        key(START, true, 100, deviceId = OTHER_PAD)
        key(START, false, 160, deviceId = OTHER_PAD)

        assertEquals(emptyList<Int>(), keys.forget(PAD))
        assertEquals(260L, keys.deadline())
        assertEquals(listOf(START), keys.forget(OTHER_PAD))
        assertNull(keys.deadline())
        assertFalse(keys.onTime(5000).openMenu)
    }
}
