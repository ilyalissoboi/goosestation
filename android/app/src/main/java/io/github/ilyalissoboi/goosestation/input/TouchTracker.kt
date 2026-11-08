// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.floor

/**
 * Turns the fingers on the touch controls into the pad's [state]. Each finger belongs to the control whose touch
 * area it went down in, for as long as it's down, and one that went down in none does nothing. On the **D-pad** it
 * presses the arm it points to from the centre, or both arms between two (eight directions), nothing near the centre,
 * and keeps steering wherever it slides. On the **face** diamond it presses every button whose centre is within 0.8 of
 * the diamond's offset: one on a button, both between two, none at the centre, so it slides from one to the next.
 * **Shoulders, Select, Start, L3 and R3** stay pressed until it lifts. On a **stick** it pushes the stick as far as it
 * is from the centre, all the way at the ring's edge or past it, and lets go as it lifts. The **menu** opens as its
 * finger lifts on it. A button held by several fingers is let go when the last lifts.
 *
 * Positions are in the [layout]'s dp. Setting a new [layout] (a rotation, a resize, the sticks coming or going) lets
 * go of everything and forgets every finger: their moves do nothing until they lift.
 */
class TouchTracker(
    layout: TouchLayout,
) {
    /** One event's result: the [state] after it, whether it [changed], how many buttons it pressed ([ticks], for the
     * press tick), and whether it opened the [menu]. */
    data class Outcome(
        val state: TouchPadState,
        val changed: Boolean,
        val ticks: Int,
        val menu: Boolean,
    )

    private class Finger(
        val control: TouchControl,
        var at: Offset,
    )

    private val fingers = LinkedHashMap<Long, Finger>()

    var state: TouchPadState = TouchPadState.RELEASED
        private set

    var layout: TouchLayout = layout
        set(value) {
            field = value
            cancel()
        }

    fun down(
        pointer: Long,
        x: Float,
        y: Float,
    ): Outcome {
        val at = Offset(x, y)
        val control = layout.controls.firstOrNull { it.touch.contains(at) } ?: return update()
        fingers[pointer] = Finger(control, at)
        return update()
    }

    fun move(
        pointer: Long,
        x: Float,
        y: Float,
    ): Outcome {
        fingers[pointer]?.at = Offset(x, y)
        return update()
    }

    fun up(pointer: Long): Outcome {
        val finger = fingers.remove(pointer) ?: return update()
        val menu = finger.control.id == TouchControlId.Menu && finger.control.touch.contains(finger.at)
        return update(menu)
    }

    /** Every finger is gone (a cancelled gesture): everything is let go. */
    fun cancel(): Outcome {
        fingers.clear()
        return update()
    }

    private fun update(menu: Boolean = false): Outcome {
        var buttons = 0
        var left = Offset.Zero
        var right = Offset.Zero
        for (finger in fingers.values) {
            when (finger.control.id) {
                TouchControlId.DPad -> buttons = buttons or dpad(finger)
                TouchControlId.Face -> buttons = buttons or face(finger.at)
                TouchControlId.LeftStick -> left = stick(finger)
                TouchControlId.RightStick -> right = stick(finger)
                TouchControlId.Menu -> {}
                else -> buttons = buttons or FIXED.getValue(finger.control.id).bit
            }
        }
        val next = TouchPadState(buttons, left.x, left.y, right.x, right.y)
        val pressed = Integer.bitCount(next.buttons and state.buttons.inv())
        val changed = next != state
        state = next
        return Outcome(next, changed, pressed, menu)
    }

    private fun dpad(finger: Finger): Int {
        val v = finger.at - finger.control.touch.center
        if (v.getDistance() < layout.dpadArm / 2) return 0
        // Clockwise from straight up, 0 to 360.
        val degrees = (Math.toDegrees(atan2(v.x.toDouble(), -v.y.toDouble())) + 360) % 360
        return DIRECTIONS[floor((degrees + 22.5) / 45).toInt() % 8]
    }

    private fun face(at: Offset): Int =
        FACE.fold(0) { buttons, button ->
            val near = (layout.faceButtonRect(button).center - at).getDistance() <= 0.8f * layout.faceOffset
            if (near) buttons or button.bit else buttons
        }

    private fun stick(finger: Finger): Offset {
        val ring = finger.control.drawn
        val v = (finger.at - ring.center) / (ring.width / 2)
        val length = v.getDistance()
        return if (length > 1f) v / length else v
    }

    private companion object {
        val FACE = listOf(TouchButton.Triangle, TouchButton.Circle, TouchButton.Cross, TouchButton.Square)

        // From straight up, clockwise, 45 degrees each.
        val DIRECTIONS =
            listOf(
                TouchButton.Up.bit,
                TouchButton.Up.bit or TouchButton.Right.bit,
                TouchButton.Right.bit,
                TouchButton.Down.bit or TouchButton.Right.bit,
                TouchButton.Down.bit,
                TouchButton.Down.bit or TouchButton.Left.bit,
                TouchButton.Left.bit,
                TouchButton.Up.bit or TouchButton.Left.bit,
            )

        val FIXED =
            mapOf(
                TouchControlId.L1 to TouchButton.L1,
                TouchControlId.L2 to TouchButton.L2,
                TouchControlId.R1 to TouchButton.R1,
                TouchControlId.R2 to TouchButton.R2,
                TouchControlId.Select to TouchButton.Select,
                TouchControlId.Start to TouchButton.Start,
                TouchControlId.L3 to TouchButton.L3,
                TouchControlId.R3 to TouchButton.R3,
            )
    }
}
