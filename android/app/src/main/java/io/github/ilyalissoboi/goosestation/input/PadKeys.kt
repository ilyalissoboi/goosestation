// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import io.github.ilyalissoboi.goosestation.input.MenuTrigger.KeyPress

/**
 * What [ControllerInput] does with each controller key, without Android: where it goes ([KeyRouter]), whether it opens
 * the menu or is held back ([MenuTrigger], and [SelectChord] on a pad with a hotkey chord), and when a late press is let
 * go ([LateRelease]). Every press sent to the core gets its key-up, and a press held back and then dropped gets none. Pure: the caller gives the times, sends what
 * it's told, and calls [onTime] at [deadline]. Main thread only.
 */
internal class PadKeys {
    /**
     * Whether the key event goes [toCore] (else to Compose), what to [send] the core for it now, in order, and whether
     * to open the menu.
     */
    data class Outcome(
        val toCore: Boolean,
        val send: List<KeyPress>,
        val openMenu: Boolean,
    )

    /**
     * The late key-ups due, to send now, whether a hold completed and the menu opens, and the devices whose Select held
     * back for its chords goes to the core now ([lateSelects]), as a key-down.
     */
    data class Tick(
        val releases: List<LateRelease.Release>,
        val openMenu: Boolean,
        val lateSelects: List<Int> = emptyList(),
    )

    private val router = KeyRouter()
    private val menuTrigger = MenuTrigger()
    private val selectChord = SelectChord()
    private val lateRelease = LateRelease()

    /**
     * A key-down ([pressed]), auto-repeat ([repeat]) or key-up, [canceled] or not, of [keyCode] on [deviceId]. It goes
     * to the core if [toCore] (the pads are the core's and it's a pad key), or if the press began there. The hold is
     * timed from the event ([eventMs]), and a late press is held down from when it's sent ([nowMs]). With
     * [selectChords] (the pad has a hotkey chord), Select's press is held back ([SelectChord]).
     */
    fun onKey(
        deviceId: Int,
        keyCode: Int,
        pressed: Boolean,
        repeat: Boolean,
        canceled: Boolean,
        toCore: Boolean,
        eventMs: Long,
        nowMs: Long,
        selectChords: Boolean = false,
    ): Outcome {
        val send = ArrayList<KeyPress>()
        // A late press of this key still waiting to be let go ends before anything else happens to the key.
        if (lateRelease.flush(deviceId, keyCode)) send += KeyPress(keyCode, false)
        if (!router.route(deviceId, keyCode, pressed, toCore, repeat)) return Outcome(false, send, openMenu = false)
        // Auto-repeat changes nothing for the pad.
        if (repeat) return Outcome(true, send, openMenu = false)

        val result =
            if (canceled && !pressed) {
                menuTrigger.cancel(deviceId, keyCode)
            } else {
                menuTrigger.onKey(deviceId, keyCode, pressed, eventMs)
            }
        result.dropped?.let { router.drop(deviceId, it) }
        val chorded = selectChord.plan(deviceId, result.send, selectChords, canceled && !pressed, eventMs)
        if (pressed && !result.openMenu && KeyPress(keyCode, true) !in chorded) {
            router.markHeldBack(deviceId, keyCode)
        }
        // A held Select whose cancelled key-up dropped it: the rest of it goes to Compose.
        if (canceled && !pressed && keyCode == MenuTrigger.SELECT && KeyPress(keyCode, false) !in chorded) {
            router.drop(deviceId, keyCode)
        }
        for (press in lateRelease.plan(deviceId, chorded, nowMs)) {
            if (press.pressed) router.markSent(deviceId, press.keyCode)
            send += press
        }
        return Outcome(true, send, result.openMenu)
    }

    /** When [onTime] must next be called, or null. */
    fun deadline(): Long? =
        listOfNotNull(menuTrigger.deadline(), selectChord.deadline(menuTrigger::isHolding), lateRelease.deadline())
            .minOrNull()

    /**
     * The late key-ups due by [nowMs], and whether a hold has completed. If one has, every press held back is
     * dropped, on every device.
     */
    fun onTime(nowMs: Long): Tick {
        val releases = lateRelease.due(nowMs)
        val open = menuTrigger.onTime(nowMs)
        if (open) dropHeldBack()
        val selects = selectChord.onTime(nowMs, menuTrigger::isHolding)
        // Its key-up follows it to the core from now on.
        for (deviceId in selects) router.markSent(deviceId, MenuTrigger.SELECT)
        return Tick(releases, open, selects)
    }

    /** Everything is let go: the late key-ups waiting, to send now. Every press held back is dropped. */
    fun releaseAll(): List<LateRelease.Release> = lateRelease.flushAll().also { dropHeldBack() }

    /** [deviceId] is gone: its late key-ups' key codes, to send now. Its presses and its hold are forgotten. */
    fun forget(deviceId: Int): List<Int> =
        lateRelease.flushDevice(deviceId).also {
            menuTrigger.forget(deviceId)
            selectChord.forget(deviceId)
            router.forget(deviceId)
        }

    private fun dropHeldBack() {
        menuTrigger.reset()
        selectChord.reset()
        router.dropHeldBack()
    }
}
