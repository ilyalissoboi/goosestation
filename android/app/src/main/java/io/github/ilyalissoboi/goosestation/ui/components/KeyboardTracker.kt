// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

/**
 * Whether the search field's keyboard is open, as far as the field can tell, so that [onClosed] hears each closing
 * once, whether the Search action closed it or Back did. Main thread only.
 *
 * The keyboard's own events can land late: Search pressed before the keyboard the field asked for is on screen has it
 * show, and hide, after its close was told. That late pair belongs to the opening already closed, and isn't told again.
 */
internal class KeyboardTracker(
    private val onClosed: () -> Unit,
) {
    private var open = false

    // Asked for, and not yet seen on screen.
    private var awaited = false

    // On screen, but for an opening Search already closed: its hiding isn't a close.
    private var late = false

    /** The field asked for the keyboard: A, the D-pad centre, a tap or `focusAndType`. */
    fun requested() {
        open = true
        awaited = true
        late = false
    }

    /** The keyboard came on screen. */
    fun shown() {
        if (awaited && !open) {
            late = true
        } else {
            open = true
        }
        awaited = false
    }

    /** The keyboard left the screen. */
    fun hidden() {
        if (late) {
            late = false
        } else {
            close()
        }
    }

    /** The keyboard's Search action, which closes it: a close even when no keyboard was seen (a hardware Enter). */
    fun searched() {
        open = true
        close()
    }

    /** The field lost the focus: a keyboard it asked for and never saw isn't coming, so the next shown is its own. */
    fun focusLost() {
        awaited = false
    }

    private fun close() {
        if (!open) return
        open = false
        onClosed()
    }
}
