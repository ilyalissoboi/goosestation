// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

/**
 * Where each controller's motion goes, as [KeyRouter] does for keys: to the core while it has the pads, and after
 * that, from a device whose stick was pushed when the core last had its motion, until that stick is back near rest.
 * Otherwise a stick a capture took, still held or on its way back, would reach the app, which Android turns into
 * D-pad keys that move the focus. Main thread only.
 */
internal class AxisHold {
    private companion object {
        /** An axis within this of 0 is at rest. */
        const val REST = 0.25f
    }

    // The devices whose last motion sent to the core had an axis pushed past REST.
    private val held = HashSet<Int>()

    /**
     * Whether a device's motion goes to the core: always while [padInput], and after that while it's held. A held
     * device's motion back within [REST] ([deflection], its furthest axis from 0) ends the hold, and still goes to the
     * core, so the core doesn't keep the last push.
     */
    fun route(
        deviceId: Int,
        padInput: Boolean,
        deflection: Float,
    ): Boolean {
        val pushed = deflection >= REST
        if (padInput) {
            if (pushed) held.add(deviceId) else held.remove(deviceId)
            return true
        }
        if (deviceId !in held) return false
        if (!pushed) held.remove(deviceId)
        return true
    }

    /** Ends [deviceId]'s hold: it's gone. */
    fun forget(deviceId: Int) {
        held.remove(deviceId)
    }
}
