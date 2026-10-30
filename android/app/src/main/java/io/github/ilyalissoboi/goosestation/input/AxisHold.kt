// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

/**
 * Where each controller's motion goes, as [KeyRouter] does for keys: to the core while it has the pads, and after
 * that, from a device whose stick was pushed when the core last had its motion, until that stick is back under the
 * device's bar ([barFor]). Otherwise a stick a capture took, still held or on its way back, would reach the app, which
 * Android turns into D-pad keys that move the focus. Main thread only.
 */
internal class AxisHold {
    /** Where one motion event goes. */
    enum class Route {
        /** To the core, as the pads' input. */
        Core,

        /** To the core, then rest (0) for the device's held axes: the hold ends, and the core doesn't keep a push. */
        CoreThenRest,

        /** To the app. */
        App,
    }

    companion object {
        /** Android turns a stick into D-pad keys at 0.5: anything under it moves no focus. */
        private const val BAR = 0.45f
        private const val MAX_BAR = 0.49f

        /** The bar for a device whose sticks declare [flat] as their dead zone: it can raise the bar, never to 0.5. */
        fun barFor(flat: Float): Float = maxOf(BAR, flat).coerceAtMost(MAX_BAR)
    }

    // The devices whose last motion sent to the core had an axis at or past their bar.
    private val held = HashSet<Int>()

    /**
     * Where a device's motion goes: always the core while [padInput], and after that while it's held. A held device's
     * motion under [bar] ([deflection], its furthest axis from 0) ends the hold with [Route.CoreThenRest].
     */
    fun route(
        deviceId: Int,
        padInput: Boolean,
        deflection: Float,
        bar: Float,
    ): Route {
        val pushed = deflection >= bar
        if (padInput) {
            if (pushed) held.add(deviceId) else held.remove(deviceId)
            return Route.Core
        }
        if (deviceId !in held) return Route.App
        if (pushed) return Route.Core
        held.remove(deviceId)
        return Route.CoreThenRest
    }

    /** Ends [deviceId]'s hold: it's gone. */
    fun forget(deviceId: Int) {
        held.remove(deviceId)
    }
}
