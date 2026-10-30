// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import io.github.ilyalissoboi.goosestation.input.AxisHold.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/** A stick pushed while the core had the pads stays the core's until it's back under Android's D-pad threshold. */
class AxisHoldTest {
    private companion object {
        const val PAD = 7
        const val OTHER_PAD = 8
        const val BAR = 0.45f
    }

    private val hold = AxisHold()

    private fun route(
        deviceId: Int,
        padInput: Boolean,
        deflection: Float,
    ) = hold.route(deviceId, padInput, deflection, BAR)

    @Test
    fun motionGoesToTheCoreWhileItHasThePads() {
        assertEquals(Route.Core, route(PAD, padInput = true, deflection = 0f))
        assertEquals(Route.Core, route(PAD, padInput = true, deflection = 0.8f))
    }

    @Test
    fun aStickHeldPastTheCapturesEndStaysTheCores() {
        route(PAD, padInput = true, deflection = 0.8f)

        assertEquals(Route.Core, route(PAD, padInput = false, deflection = 0.8f))
        assertEquals(Route.Core, route(PAD, padInput = false, deflection = 0.5f))
    }

    // Review Focus 1: a stick that drifts at 0.3 navigates again at once.
    @Test
    fun driftBelowTheBarEndsTheHold() {
        route(PAD, padInput = true, deflection = 0.8f)

        assertEquals(Route.CoreThenRest, route(PAD, padInput = false, deflection = 0.3f))
        assertEquals(Route.App, route(PAD, padInput = false, deflection = 0.3f))
    }

    @Test
    fun aHoldEndsWithRestOnce() {
        route(PAD, padInput = true, deflection = 0.8f)
        route(PAD, padInput = false, deflection = 0.6f)

        assertEquals(Route.CoreThenRest, route(PAD, padInput = false, deflection = 0.1f))
        assertEquals(Route.App, route(PAD, padInput = false, deflection = 0.1f))
        assertEquals(Route.App, route(PAD, padInput = false, deflection = 0.8f))
    }

    @Test
    fun aStickUnderTheBarAsTheCaptureEndsIsntHeld() {
        route(PAD, padInput = true, deflection = 0.8f)
        route(PAD, padInput = true, deflection = 0.3f)

        assertEquals(Route.App, route(PAD, padInput = false, deflection = 0.6f))
    }

    @Test
    fun anotherDevicesMotionIsntHeld() {
        route(PAD, padInput = true, deflection = 0.8f)

        assertEquals(Route.App, route(OTHER_PAD, padInput = false, deflection = 0.8f))
        assertEquals(Route.Core, route(PAD, padInput = false, deflection = 0.8f))
    }

    @Test
    fun aDisconnectEndsTheHold() {
        route(PAD, padInput = true, deflection = 0.8f)
        route(OTHER_PAD, padInput = true, deflection = 0.8f)

        hold.forget(PAD)

        assertEquals(Route.App, route(PAD, padInput = false, deflection = 0.8f))
        assertEquals(Route.Core, route(OTHER_PAD, padInput = false, deflection = 0.8f))
    }

    @Test
    fun theBarIsHeldUntilTheValueIsUnderIt() {
        route(PAD, padInput = true, deflection = BAR)

        assertEquals(Route.Core, route(PAD, padInput = false, deflection = BAR))
        assertEquals(Route.CoreThenRest, route(PAD, padInput = false, deflection = 0.44f))
    }

    @Test
    fun aLargerFlatRaisesTheBarButNeverToHalf() {
        assertEquals(0.45f, AxisHold.barFor(0f), 0f)
        assertEquals(0.47f, AxisHold.barFor(0.47f), 0f)
        assertEquals(0.49f, AxisHold.barFor(0.6f), 0f)
    }
}
