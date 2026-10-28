// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import io.github.ilyalissoboi.goosestation.input.MenuTrigger.KeyPress

/**
 * Holds a press sent late all at once ([MenuTrigger]'s `[it down, it up]`) down for [holdMs], so the game, which reads
 * the pad once a frame, sees it. Its key-up waits, and goes once it's [due], or at once ([flush]) before anything else
 * for that key, and when everything is let go. Pure: the caller gives the time, and calls [due] at [deadline].
 */
internal class LateRelease(private val holdMs: Long = 100) {
    /** A waiting key-up to send now: [keyCode]'s on [deviceId]. */
    data class Release(val deviceId: Int, val keyCode: Int)

    // The waiting key-ups, (device, key code) to when each is due, in the order they were held.
    private val waiting = LinkedHashMap<Release, Long>()

    /**
     * What to send now of [presses], one key event's on [deviceId] at [nowMs]: a key-up right after its own key-down
     * waits [holdMs].
     */
    fun plan(
        deviceId: Int,
        presses: List<KeyPress>,
        nowMs: Long,
    ): List<KeyPress> {
        val now = ArrayList<KeyPress>(presses.size)
        for (press in presses) {
            if (!press.pressed && now.lastOrNull() == KeyPress(press.keyCode, true)) {
                waiting[Release(deviceId, press.keyCode)] = nowMs + holdMs
            } else {
                now += press
            }
        }
        return now
    }

    /** Whether [keyCode]'s key-up on [deviceId] was waiting: it's no longer, and the caller sends it now. */
    fun flush(
        deviceId: Int,
        keyCode: Int,
    ): Boolean = waiting.remove(Release(deviceId, keyCode)) != null

    /** [deviceId]'s waiting key-ups' key codes, to send now: it's going. */
    fun flushDevice(deviceId: Int): List<Int> {
        val gone = waiting.keys.filter { it.deviceId == deviceId }
        waiting.keys -= gone.toSet()
        return gone.map { it.keyCode }
    }

    /** Every waiting key-up, to send now. */
    fun flushAll(): List<Release> = waiting.keys.toList().also { waiting.clear() }

    /** When the next waiting key-up is due, or null with none. */
    fun deadline(): Long? = waiting.values.minOrNull()

    /** The key-ups due by [nowMs], to send now. */
    fun due(nowMs: Long): List<Release> {
        val due = waiting.filterValues { nowMs >= it }.keys.toList()
        waiting.keys -= due.toSet()
        return due
    }
}
