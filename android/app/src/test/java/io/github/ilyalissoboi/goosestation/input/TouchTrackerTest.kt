// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import androidx.compose.ui.geometry.Offset
import io.github.ilyalissoboi.goosestation.input.TouchButton.Circle
import io.github.ilyalissoboi.goosestation.input.TouchButton.Cross
import io.github.ilyalissoboi.goosestation.input.TouchButton.Down
import io.github.ilyalissoboi.goosestation.input.TouchButton.L1
import io.github.ilyalissoboi.goosestation.input.TouchButton.Left
import io.github.ilyalissoboi.goosestation.input.TouchButton.R1
import io.github.ilyalissoboi.goosestation.input.TouchButton.Right
import io.github.ilyalissoboi.goosestation.input.TouchButton.Up
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Fingers on the touch controls: the buttons and sticks they hold, their slides, and their release. */
class TouchTrackerTest {
    private val digital = TouchLayout.of(800f, 370f, false, 1f)
    private val analog = TouchLayout.of(800f, 370f, true, 1f)
    private val tracker = TouchTracker(digital)

    private fun centre(id: TouchControlId) =
        tracker.layout
            .getValue(id)
            .drawn.center

    private fun face(button: TouchButton) = tracker.layout.faceButtonRect(button).center

    // On the D-pad, [degrees] clockwise from straight up, at 0.8 of its reach.
    private fun dpad(degrees: Double): Offset {
        val reach =
            tracker.layout
                .getValue(TouchControlId.DPad)
                .touch.width / 2 * 0.8f
        val angle = Math.toRadians(degrees)
        return centre(TouchControlId.DPad) + Offset((sin(angle) * reach).toFloat(), (-cos(angle) * reach).toFloat())
    }

    private fun bits(vararg buttons: TouchButton) = buttons.fold(0) { acc, b -> acc or b.bit }

    private fun TouchTracker.down(
        pointer: Long,
        at: Offset,
    ) = down(pointer, at.x, at.y)

    private fun TouchTracker.move(
        pointer: Long,
        at: Offset,
    ) = move(pointer, at.x, at.y)

    @Test
    fun dpadArms() {
        for ((degrees, button) in listOf(0.0 to Up, 90.0 to Right, 180.0 to Down, 270.0 to Left)) {
            assertEquals("$degrees", bits(button), tracker.down(1, dpad(degrees)).state.buttons)
            tracker.up(1)
        }
    }

    @Test
    fun dpadDiagonalsPressBoth() {
        assertEquals(bits(Up, Right), tracker.down(1, dpad(45.0)).state.buttons)
        tracker.up(1)
        assertEquals(bits(Down, Left), tracker.down(1, dpad(225.0)).state.buttons)
    }

    @Test
    fun dpadCentreIsNothing() {
        assertEquals(0, tracker.down(1, centre(TouchControlId.DPad)).state.buttons)
    }

    @Test
    fun dpadSlidesAcrossArms() {
        tracker.down(1, dpad(270.0))
        val moved = tracker.move(1, dpad(0.0))
        assertEquals(bits(Up), moved.state.buttons)
        assertEquals(1, moved.ticks)
    }

    @Test
    fun dpadKeepsSteeringOutsideItsArea() {
        tracker.down(1, dpad(0.0))
        val far = centre(TouchControlId.DPad) + Offset(0f, 300f)
        assertEquals(bits(Down), tracker.move(1, far).state.buttons)
    }

    @Test
    fun faceSlidesBetweenButtons() {
        val first = tracker.down(1, face(Cross))
        assertEquals(bits(Cross), first.state.buttons)
        assertEquals(1, first.ticks)
        val between = tracker.move(1, (face(Cross) + face(Circle)) / 2f)
        assertEquals(bits(Cross, Circle), between.state.buttons)
        assertEquals(1, between.ticks)
        val circle = tracker.move(1, face(Circle))
        assertEquals(bits(Circle), circle.state.buttons)
        assertEquals(0, circle.ticks)
    }

    @Test
    fun faceCentreIsNothing() {
        assertEquals(0, tracker.down(1, centre(TouchControlId.Face)).state.buttons)
    }

    @Test
    fun aShoulderKeepsItsFinger() {
        assertEquals(bits(L1), tracker.down(1, centre(TouchControlId.L1)).state.buttons)
        assertEquals(bits(L1), tracker.move(1, centre(TouchControlId.L2)).state.buttons)
        assertEquals(0, tracker.up(1).state.buttons)
    }

    @Test
    fun twoFingersAtOnce() {
        tracker.down(1, dpad(0.0))
        tracker.down(2, face(Cross))
        assertEquals(bits(Up, Cross, R1), tracker.down(3, centre(TouchControlId.R1)).state.buttons)
        assertEquals(bits(Up, R1), tracker.up(2).state.buttons)
    }

    @Test
    fun oneButtonTwoFingers() {
        tracker.down(1, face(Cross))
        val second = tracker.down(2, face(Cross))
        assertEquals(0, second.ticks)
        assertFalse(second.changed)
        assertEquals(bits(Cross), tracker.up(1).state.buttons)
        assertEquals(0, tracker.up(2).state.buttons)
    }

    @Test
    fun cancelReleasesEverything() {
        tracker.down(1, dpad(45.0))
        tracker.down(2, face(Cross))
        val cancelled = tracker.cancel()
        assertEquals(TouchPadState.RELEASED, cancelled.state)
        assertTrue(cancelled.changed)
        // The fingers are forgotten: their next moves do nothing.
        assertFalse(tracker.move(1, dpad(0.0)).changed)
    }

    @Test
    fun liftingEveryFingerReleasesEverything() {
        tracker.down(1, dpad(45.0))
        tracker.down(2, centre(TouchControlId.Start))
        tracker.up(2)
        assertEquals(TouchPadState.RELEASED, tracker.up(1).state)
    }

    @Test
    fun aTouchOutsideEveryControlDoesNothing() {
        val nothing = tracker.down(1, digital.picture.center)
        assertFalse(nothing.changed)
        assertFalse(tracker.move(1, face(Cross)).changed)
    }

    @Test
    fun stickFollowsAndSpringsBack() {
        tracker.layout = analog
        val stick = analog.getValue(TouchControlId.LeftStick).drawn
        val radius = stick.width / 2
        val half = tracker.down(1, stick.center + Offset(radius / 2, 0f))
        assertEquals(0.5f, half.state.leftX, 0.01f)
        assertEquals(0f, half.state.leftY, 0.01f)
        assertEquals(0, half.ticks)
        val past = tracker.move(1, stick.center + Offset(radius * 2, radius * 2)).state
        assertEquals(1f, sqrt(past.leftX * past.leftX + past.leftY * past.leftY), 0.01f)
        assertEquals(past.leftX, past.leftY, 0.01f)
        assertEquals(TouchPadState.RELEASED, tracker.up(1).state)
    }

    @Test
    fun aTouchOnTheRingTakesTheStick() {
        tracker.layout = analog
        val stick = analog.getValue(TouchControlId.RightStick).drawn
        val state = tracker.down(1, stick.center + Offset(0f, -stick.width / 2 * 0.95f)).state
        assertEquals(-0.95f, state.rightY, 0.01f)
        assertEquals(0f, state.leftY, 0.01f)
    }

    @Test
    fun theMenuOpensOnItsLift() {
        val menu = centre(TouchControlId.Menu)
        assertFalse(tracker.down(1, menu).menu)
        assertTrue(tracker.up(1).menu)
        tracker.down(2, menu)
        tracker.move(2, centre(TouchControlId.DPad))
        assertFalse("lifted elsewhere", tracker.up(2).menu)
    }

    @Test
    fun ticksOnlyOnPresses() {
        assertEquals(1, tracker.down(1, centre(TouchControlId.L1)).ticks)
        assertEquals(0, tracker.up(1).ticks)
        assertEquals(0, tracker.down(2, centre(TouchControlId.Menu)).ticks)
        assertEquals(2, tracker.down(3, dpad(45.0)).ticks)
    }

    @Test
    fun aNewLayoutReleasesEverything() {
        tracker.down(1, dpad(0.0))
        tracker.down(2, centre(TouchControlId.L1))
        tracker.layout = TouchLayout.of(360f, 800f, false, 1f)
        assertEquals(TouchPadState.RELEASED, tracker.state)
        assertFalse(tracker.move(1, dpad(90.0)).changed)
        assertFalse(tracker.up(2).changed)
    }

    @Test
    fun aControlThatGoesReleasesItsFingers() {
        tracker.layout = analog
        val stick = analog.getValue(TouchControlId.LeftStick).drawn
        tracker.down(1, stick.center + Offset(stick.width / 4, 0f))
        tracker.layout = digital
        assertEquals(0f, tracker.state.leftX)
        assertFalse(tracker.move(1, centre(TouchControlId.DPad) + Offset(0f, -50f)).changed)
    }
}
