// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

/**
 * The touch pad's buttons, in the order of their bits in [TouchPadState.buttons]. The core takes each as the Port 1
 * input with the same generic name (`DPadUp`, …, `R3`; android_host.cpp's touch pad).
 */
enum class TouchButton {
    Up,
    Right,
    Down,
    Left,
    Triangle,
    Circle,
    Cross,
    Square,
    Select,
    Start,
    L1,
    R1,
    L2,
    R2,
    L3,
    R3,
    ;

    val bit: Int get() = 1 shl ordinal
}

/**
 * Everything the touch pad holds: its [buttons] ([TouchButton.bit]s) and both sticks, x and y each -1 to 1, y down.
 */
data class TouchPadState(
    val buttons: Int = 0,
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
) {
    fun has(button: TouchButton) = buttons and button.bit != 0

    companion object {
        val RELEASED = TouchPadState()
    }
}

/** What Port 1's controller is to the touch pad, as the core reports it (NativeCallbacks.onPadKindChanged). */
enum class PadKind {
    /** Not a pad the touch controls can be: a light gun, the mouse, a NeGcon, or nothing. */
    Unsupported,

    /** A digital pad, or a DualShock with analog mode off: no sticks. */
    Digital,

    /** A DualShock in analog mode, or an analog joystick: with sticks. */
    Analog,
    ;

    companion object {
        /** The core's 0, 1 or 2; null for -1, no game. */
        fun fromCore(kind: Int): PadKind? = entries.getOrNull(kind)
    }
}
