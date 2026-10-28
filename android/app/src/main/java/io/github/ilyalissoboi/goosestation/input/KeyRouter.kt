// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

/**
 * Where each controller key goes: to the core, or on to Compose. A key-down goes where it's sent; its repeats and its
 * key-up follow it there, whatever changed meanwhile, so each press ends where it started. A capture ending on a
 * key-down, or a screen change while a key is held, leaves no release for Compose to act on. Main thread only.
 */
internal class KeyRouter {
    // The (device, key code) pairs whose key-down went to the core, and whose key-up hasn't come yet.
    private val inCore = HashSet<Long>()

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
        if (!pressed) return inCore.remove(key)
        if (repeat) return key in inCore
        if (toCore) inCore.add(key) else inCore.remove(key)
        return toCore
    }

    /** Forgets [deviceId]'s presses: it's gone, and sends no more key-ups. */
    fun forget(deviceId: Int) {
        inCore.removeAll { (it ushr 32).toInt() == deviceId }
    }

    private fun key(
        deviceId: Int,
        keyCode: Int,
    ): Long = (deviceId.toLong() shl 32) or (keyCode.toLong() and 0xffffffffL)
}
