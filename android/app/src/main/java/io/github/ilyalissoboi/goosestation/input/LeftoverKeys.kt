// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import android.view.KeyEvent

/**
 * The keys the activity takes when the screen leaves them. A key the window leaves unhandled comes back as the
 * fallback key its device's key character map gives for it: from adb's virtual keyboard, Y becomes Back and X becomes
 * DPAD_CENTER; on the AYN Thor's own pad, Y becomes Space (a press, to Compose's `clickable`) and X becomes Delete. So
 * away from a game, X and Y that nothing on the screen used would go back a page, press the focused button or leave
 * the app. A screen that uses them (a game's tile, a save's row) still gets them first.
 *
 * A and B are left alone: A's DPAD_CENTER is how A presses anything, and B's Back is how B goes back. Pure.
 */
internal object LeftoverKeys {
    /** Whether the activity takes [keyCode], key-down and key-up, when the screen left it; never while [padInput]. */
    fun takes(
        keyCode: Int,
        padInput: Boolean,
    ): Boolean = !padInput && (keyCode == KeyEvent.KEYCODE_BUTTON_X || keyCode == KeyEvent.KEYCODE_BUTTON_Y)
}
