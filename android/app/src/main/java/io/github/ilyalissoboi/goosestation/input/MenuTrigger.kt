// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

/**
 * Decides, in front of the core, which of a controller's keys open the game's menu, so the game never sees them: Mode,
 * and Select and Start held together for [holdMs] on one device. The second of the pair to go down is held back until
 * the hold completes, when it's dropped, or until one of them goes up, when it's sent late. Every other key, and Select
 * or Start alone, is sent as it comes. Pure: the caller gives the time, and calls [onTime] at [deadline].
 */
class MenuTrigger(private val holdMs: Long = 1000) {
    companion object {
        const val KEYCODE_BUTTON_MODE = 110
        const val SELECT = 109
        const val START = 108
    }

    data class KeyPress(val keyCode: Int, val pressed: Boolean)

    /**
     * The key events to [send] to the core, in order, whether to open the menu, and the key whose press held back was
     * [dropped], never to be sent ([cancel]).
     */
    data class Result(val send: List<KeyPress>, val openMenu: Boolean, val dropped: Int? = null)

    // Each device's Select and Start that are down, sent or not, and the one held back with when its hold completes.
    private class Pad {
        val down = HashSet<Int>()
        var heldBack: Int? = null
        var deadline = 0L
    }

    private val pads = HashMap<Int, Pad>()

    fun onKey(
        deviceId: Int,
        keyCode: Int,
        pressed: Boolean,
        nowMs: Long,
    ): Result {
        if (keyCode == KEYCODE_BUTTON_MODE) return Result(emptyList(), openMenu = pressed)
        if (keyCode != SELECT && keyCode != START) return send(KeyPress(keyCode, pressed))

        val pad = pads.getOrPut(deviceId) { Pad() }
        val heldBack = pad.heldBack
        if (pressed) {
            val other = if (keyCode == SELECT) START else SELECT
            pad.down += keyCode
            if (other !in pad.down || heldBack != null) return send(KeyPress(keyCode, true))
            pad.heldBack = keyCode
            pad.deadline = nowMs + holdMs
            return Result(emptyList(), openMenu = false)
        }

        pad.down -= keyCode
        pad.heldBack = null
        return when (heldBack) {
            null -> send(KeyPress(keyCode, false))
            // Let go before the hold completed: the press, late.
            keyCode -> send(KeyPress(keyCode, true), KeyPress(keyCode, false))
            // The first let go: the one held back is an ordinary pressed key from now on.
            else -> send(KeyPress(keyCode, false), KeyPress(heldBack, true))
        }
    }

    /** When [onTime] must next be called: the soonest hold's end, or null with none. */
    fun deadline(): Long? = pads.values.filter { it.heldBack != null }.minOfOrNull { it.deadline }

    /**
     * Whether a hold has completed by [nowMs]: the menu opens, and the press held back is dropped. That device's state
     * is cleared.
     */
    fun onTime(nowMs: Long): Boolean {
        val done = pads.filterValues { it.heldBack != null && nowMs >= it.deadline }.keys
        pads.keys -= done
        return done.isNotEmpty()
    }

    /**
     * A cancelled key-up ([android.view.KeyEvent.FLAG_CANCELED]: the window lost focus, say), which ends the chord
     * without the user finishing it. The press held back is [Result.dropped], whichever of the two was cancelled, and
     * never sent. A key that was sent is let go, as on any key-up.
     */
    fun cancel(
        deviceId: Int,
        keyCode: Int,
    ): Result {
        val pad = pads[deviceId]
        val heldBack = pad?.heldBack
        if (heldBack == null || (keyCode != SELECT && keyCode != START)) return onKey(deviceId, keyCode, false, 0)

        pad.heldBack = null
        pad.down -= keyCode
        pad.down -= heldBack
        val send = if (keyCode == heldBack) emptyList() else listOf(KeyPress(keyCode, false))
        return Result(send, openMenu = false, dropped = heldBack)
    }

    /** Forgets [deviceId]'s keys and its hold: it's gone. */
    fun forget(deviceId: Int) {
        pads.remove(deviceId)
    }

    /** Forgets every key down and every press held back: they were let go. */
    fun reset() {
        pads.clear()
    }

    private fun send(vararg presses: KeyPress) = Result(presses.toList(), openMenu = false)
}
