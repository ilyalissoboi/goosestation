// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stick pushed while the core had the pads stays the core's until it's back near rest. */
class AxisHoldTest {
    private companion object {
        const val PAD = 7
        const val OTHER_PAD = 8
    }

    private val hold = AxisHold()

    @Test
    fun motionGoesToTheCoreWhileItHasThePads() {
        assertTrue(hold.route(PAD, padInput = true, deflection = 0f))
        assertTrue(hold.route(PAD, padInput = true, deflection = 0.8f))
    }

    @Test
    fun aStickHeldPastTheCapturesEndStaysTheCores() {
        hold.route(PAD, padInput = true, deflection = 0.8f)

        assertTrue(hold.route(PAD, padInput = false, deflection = 0.8f))
        assertTrue(hold.route(PAD, padInput = false, deflection = 0.4f))
    }

    @Test
    fun backNearRestTheMotionIsTheAppsAgain() {
        hold.route(PAD, padInput = true, deflection = 0.8f)
        hold.route(PAD, padInput = false, deflection = 0.6f)

        // The core still gets the stick coming back, so it doesn't keep the last push.
        assertTrue(hold.route(PAD, padInput = false, deflection = 0.1f))
        assertFalse(hold.route(PAD, padInput = false, deflection = 0.1f))
        assertFalse(hold.route(PAD, padInput = false, deflection = 0.8f))
    }

    @Test
    fun aStickAtRestAsTheCaptureEndsIsntHeld() {
        hold.route(PAD, padInput = true, deflection = 0.8f)
        hold.route(PAD, padInput = true, deflection = 0.1f)

        assertFalse(hold.route(PAD, padInput = false, deflection = 0.6f))
    }

    @Test
    fun anotherDevicesMotionIsntHeld() {
        hold.route(PAD, padInput = true, deflection = 0.8f)

        assertFalse(hold.route(OTHER_PAD, padInput = false, deflection = 0.8f))
        assertTrue(hold.route(PAD, padInput = false, deflection = 0.8f))
    }

    @Test
    fun aDisconnectEndsTheHold() {
        hold.route(PAD, padInput = true, deflection = 0.8f)
        hold.route(OTHER_PAD, padInput = true, deflection = 0.8f)

        hold.forget(PAD)

        assertFalse(hold.route(PAD, padInput = false, deflection = 0.8f))
        assertTrue(hold.route(OTHER_PAD, padInput = false, deflection = 0.8f))
    }

    @Test
    fun deflectionUnderAQuarterIsRest() {
        hold.route(PAD, padInput = true, deflection = 0.25f)

        assertTrue(hold.route(PAD, padInput = false, deflection = 0.24f))
        assertFalse(hold.route(PAD, padInput = false, deflection = 0.3f))
    }
}
