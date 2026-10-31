// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import io.github.ilyalissoboi.goosestation.input.MenuTrigger.Companion.KEYCODE_BUTTON_MODE
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.Companion.SELECT
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.Companion.START
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.KeyPress
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The menu's triggers: Mode, and Select + Start held together for a second, which the game never sees. */
class MenuTriggerTest {
    private companion object {
        const val PAD = 1
        const val OTHER_PAD = 2
        const val BUTTON_A = 96
    }

    private val trigger = MenuTrigger()

    private fun sent(vararg presses: KeyPress) = Result(presses.toList(), openMenu = false)

    private fun down(keyCode: Int) = KeyPress(keyCode, pressed = true)

    private fun up(keyCode: Int) = KeyPress(keyCode, pressed = false)

    @Test
    fun modeOpensMenu() {
        assertEquals(Result(emptyList(), openMenu = true), trigger.onKey(PAD, KEYCODE_BUTTON_MODE, true, 0))
        assertEquals(sent(), trigger.onKey(PAD, KEYCODE_BUTTON_MODE, false, 50))
    }

    @Test
    fun selectAloneIsForwarded() {
        assertEquals(sent(down(SELECT)), trigger.onKey(PAD, SELECT, true, 0))
        assertEquals(sent(up(SELECT)), trigger.onKey(PAD, SELECT, false, 2000))
        assertNull(trigger.deadline())
    }

    @Test
    fun holdingBothOpens() {
        val presses = mutableListOf<KeyPress>()
        presses += trigger.onKey(PAD, SELECT, true, 0).send
        assertEquals(listOf(down(SELECT)), presses)

        val start = trigger.onKey(PAD, START, true, 100)
        presses += start.send
        assertEquals(sent(), start)
        assertEquals(1100L, trigger.deadline())

        assertFalse(trigger.onTime(1099))
        assertTrue(trigger.onTime(1100))
        assertNull(trigger.deadline())
        assertFalse(down(START) in presses)
    }

    @Test
    fun chordLetGoEarly() {
        trigger.onKey(PAD, SELECT, true, 0)
        trigger.onKey(PAD, START, true, 100)

        assertEquals(sent(down(START), up(START)), trigger.onKey(PAD, START, false, 400))
        assertNull(trigger.deadline())
    }

    @Test
    fun firstLetGoEarly() {
        trigger.onKey(PAD, SELECT, true, 0)
        trigger.onKey(PAD, START, true, 100)

        assertEquals(sent(up(SELECT), down(START)), trigger.onKey(PAD, SELECT, false, 300))
        assertNull(trigger.deadline())
        // An ordinary pressed key from then on.
        assertEquals(sent(up(START)), trigger.onKey(PAD, START, false, 2000))
    }

    @Test
    fun pairIsPerDevice() {
        assertEquals(sent(down(SELECT)), trigger.onKey(PAD, SELECT, true, 0))
        assertEquals(sent(down(START)), trigger.onKey(OTHER_PAD, START, true, 100))
        assertNull(trigger.deadline())
    }

    @Test
    fun otherKeysPass() {
        assertEquals(sent(down(BUTTON_A)), trigger.onKey(PAD, BUTTON_A, true, 0))
    }

    @Test
    fun resetForgetsTheHold() {
        trigger.onKey(PAD, SELECT, true, 0)
        trigger.onKey(PAD, START, true, 100)

        trigger.reset()

        assertNull(trigger.deadline())
        assertFalse(trigger.onTime(5000))
    }

    @Test
    fun aCancelledHeldBackPressSendsNothing() {
        trigger.onKey(PAD, SELECT, true, 0)
        trigger.onKey(PAD, START, true, 100)

        // The window lost focus mid-chord: Start never happened.
        assertEquals(Result(emptyList(), openMenu = false, dropped = START), trigger.cancel(PAD, START))
        assertNull(trigger.deadline())
        assertEquals(sent(up(SELECT)), trigger.onKey(PAD, SELECT, false, 300))
    }

    @Test
    fun aCancelledFirstKeyIsLetGoAndDropsTheOneHeldBack() {
        trigger.onKey(PAD, SELECT, true, 0)
        trigger.onKey(PAD, START, true, 100)

        assertEquals(Result(listOf(up(SELECT)), openMenu = false, dropped = START), trigger.cancel(PAD, SELECT))
        assertNull(trigger.deadline())
        assertFalse(trigger.onTime(5000))
    }

    @Test
    fun aCancelledKeyWithNothingHeldBackIsLetGo() {
        trigger.onKey(PAD, SELECT, true, 0)

        assertEquals(sent(up(SELECT)), trigger.cancel(PAD, SELECT))
        assertEquals(sent(up(BUTTON_A)), trigger.cancel(PAD, BUTTON_A))
        assertEquals(sent(), trigger.cancel(PAD, KEYCODE_BUTTON_MODE))
    }

    @Test
    fun aGonePadsHoldIsForgotten() {
        trigger.onKey(PAD, SELECT, true, 0)
        trigger.onKey(PAD, START, true, 100)
        trigger.onKey(OTHER_PAD, SELECT, true, 0)
        trigger.onKey(OTHER_PAD, START, true, 200)

        trigger.forget(PAD)

        assertEquals(1200L, trigger.deadline())
        assertFalse(trigger.onTime(1100))
    }

    @Test
    fun isHoldingFollowsThePair() {
        val trigger = MenuTrigger()
        trigger.onKey(1, SELECT, true, 0)
        assertFalse(trigger.isHolding(1))

        trigger.onKey(1, START, true, 100)
        assertTrue(trigger.isHolding(1))
        assertFalse(trigger.isHolding(2))

        trigger.onKey(1, START, false, 300)
        assertFalse(trigger.isHolding(1))
    }
}
