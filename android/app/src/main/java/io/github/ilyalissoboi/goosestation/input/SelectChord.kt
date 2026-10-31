// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import io.github.ilyalissoboi.goosestation.input.MenuTrigger.Companion.SELECT
import io.github.ilyalissoboi.goosestation.input.MenuTrigger.KeyPress

/**
 * Holds Select's press back on a pad with a hotkey chord (Select + a button), so the game doesn't see it before the
 * chord completes. It goes to the core on Select's release (as a tap), together with the next button pressed (so the
 * core's chord fires and cancels both presses for the game, in one batch), or once it has been held for [holdMs] (for
 * a game that wants Select held). A held press's cancelled release drops it. Pure: the caller gives the time, and calls
 * [onTime] at [deadline]. Main thread only.
 */
internal class SelectChord(
    private val holdMs: Long = 500,
) {
    // By device, when the Select held back goes to the core anyway.
    private val held = HashMap<Int, Long>()

    /**
     * What to send the core in place of [presses], one key event's on [deviceId] at [nowMs], in order: with [chords]
     * (the pad has a hotkey bound), Select's press is held back. A [canceled] key event's Select release drops it.
     */
    fun plan(
        deviceId: Int,
        presses: List<KeyPress>,
        chords: Boolean,
        canceled: Boolean,
        nowMs: Long,
    ): List<KeyPress> {
        val send = ArrayList<KeyPress>(presses.size + 1)
        for (press in presses) {
            val holding = deviceId in held
            when {
                press.keyCode == SELECT && press.pressed && chords && !holding -> held[deviceId] = nowMs + holdMs
                press.keyCode == SELECT && !press.pressed && holding -> {
                    held -= deviceId
                    if (!canceled) send += listOf(KeyPress(SELECT, true), press)
                }
                press.pressed && holding -> {
                    held -= deviceId
                    send += listOf(KeyPress(SELECT, true), press)
                }
                else -> send += press
            }
        }
        return send
    }

    /** The soonest hold's end among the devices [paired] says no for (a Select + Start hold isn't under way), or null. */
    fun deadline(paired: (Int) -> Boolean): Long? = held.filterKeys { !paired(it) }.values.minOrNull()

    /** The devices whose held Select goes to the core now, as of [nowMs], but those [paired]. Their holds end. */
    fun onTime(
        nowMs: Long,
        paired: (Int) -> Boolean,
    ): List<Int> {
        val due = held.filter { (deviceId, at) -> nowMs >= at && !paired(deviceId) }.keys.toList()
        held.keys -= due.toSet()
        return due
    }

    /** [deviceId] is gone: its hold is dropped, never sent. */
    fun forget(deviceId: Int) {
        held -= deviceId
    }

    /** Every hold is dropped, never sent: everything was let go, or the menu opened. */
    fun reset() {
        held.clear()
    }
}
