// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

/**
 * Where each controller key goes: to the core, or on to Compose. A key-down goes where it's sent; its repeats and its
 * key-up follow it there, whatever changed meanwhile, so each press ends where it started. A capture ending on a
 * key-down, or a screen change while a key is held, leaves no release for Compose to act on.
 *
 * A key-down going to the core can be held back there ([MenuTrigger]), and sent late or dropped. A dropped press never
 * reached the core, so the rest of it goes to Compose. Main thread only.
 */
internal class KeyRouter {
    // The (device, key code) pairs whose key-down went to the core, and whose key-up hasn't come yet.
    private val inCore = HashSet<Long>()

    // Those of inCore whose key-down is held back, not sent yet.
    private val heldBack = HashSet<Long>()

    /**
     * Whether a key event goes to the core: a press's first key-down ([pressed]) when [toCore], and its auto-repeated
     * key-downs ([repeat]) and its key-up when that first key-down went there.
     */
    fun route(
        deviceId: Int,
        keyCode: Int,
        pressed: Boolean,
        toCore: Boolean,
        repeat: Boolean = false,
    ): Boolean {
        val key = key(deviceId, keyCode)
        if (repeat) return key in inCore
        heldBack.remove(key)
        if (!pressed) return inCore.remove(key)
        if (toCore) inCore.add(key) else inCore.remove(key)
        return toCore
    }

    /** The key-down of a press [route] sent to the core is held back there, not sent yet. */
    fun markHeldBack(
        deviceId: Int,
        keyCode: Int,
    ) {
        val key = key(deviceId, keyCode)
        if (key in inCore) heldBack.add(key)
    }

    /** A press held back was sent after all: it's the core's to the end. */
    fun markSent(
        deviceId: Int,
        keyCode: Int,
    ) {
        heldBack.remove(key(deviceId, keyCode))
    }

    /** Drops every press held back: their repeats and key-ups go to Compose. */
    fun dropHeldBack() {
        inCore.removeAll(heldBack)
        heldBack.clear()
    }

    /** Forgets [deviceId]'s presses: it's gone, and sends no more key-ups. */
    fun forget(deviceId: Int) {
        inCore.removeAll { (it ushr 32).toInt() == deviceId }
        heldBack.removeAll { (it ushr 32).toInt() == deviceId }
    }

    private fun key(
        deviceId: Int,
        keyCode: Int,
    ): Long = (deviceId.toLong() shl 32) or (keyCode.toLong() and 0xffffffffL)
}
