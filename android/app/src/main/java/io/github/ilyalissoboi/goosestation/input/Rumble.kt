// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import kotlin.math.roundToInt

/** A controller's motors Android can drive: [count] of them (0, 1, or 2, a large and a small one). */
interface Motors {
    val count: Int

    /** Runs the large motor at [large] and the small one at [small], 0 to 1; one motor takes [large]. */
    fun set(
        large: Float,
        small: Float,
    )

    fun off()
}

/**
 * Plays the core's rumble on the controllers' own motors. Each [onRumble] is passed to [post], so neither the core's
 * thread nor the main thread waits on a vibrator; [motorsFor] finds a device's motors there, and must be safe to call
 * from it. A level stays on until the next: the core repeats a level above 0 every half second, and sends 0 to stop.
 */
class Rumble(
    private val motorsFor: (deviceId: Int) -> Motors?,
    private val post: (Runnable) -> Unit,
) {
    // The devices whose motors are on, on post's thread.
    private val on = mutableSetOf<Int>()

    /** The core's levels for [deviceId], 0 to 1, already times the controller's strength. */
    fun onRumble(
        deviceId: Int,
        large: Float,
        small: Float,
    ) = post(
        Runnable {
            val motors = motorsFor(deviceId)?.takeIf { it.count > 0 } ?: return@Runnable
            when {
                large <= 0f && small <= 0f -> {
                    motors.off()
                    on.remove(deviceId)
                }
                motors.count == 1 -> {
                    motors.set(maxOf(large, small), 0f)
                    on.add(deviceId)
                }
                else -> {
                    motors.set(large, small)
                    on.add(deviceId)
                }
            }
        },
    )

    /** Stops every motor still on: the game ended, or its menu opened. */
    fun stopAll() =
        post(
            Runnable {
                for (deviceId in on) motorsFor(deviceId)?.off()
                on.clear()
            },
        )
}

/** How a level becomes a vibrator's amplitude. */
object RumbleLevels {
    /** A vibrator's own strength, for one without amplitude control ([VibrationEffect.DEFAULT_AMPLITUDE]). */
    const val DEFAULT = -1

    /** How long each level plays: longer than the core's half second between repeats, so it doesn't gap. */
    const val MS = 1_000L

    /** [level] (above 0) as an amplitude, 1 to 255; [DEFAULT] without amplitude [control]. */
    fun amplitude(
        level: Float,
        control: Boolean,
    ): Int = if (control) (level * 255).roundToInt().coerceIn(1, 255) else DEFAULT
}

/**
 * The [device]'s own motors: its vibrators on Android 12 and later (the first large, the second small), its vibrator
 * before. None for a device that isn't external: a handheld's built-in controls would drive the handheld's own motor.
 */
class DeviceMotors(
    private val device: InputDevice,
) : Motors {
    private val ids: IntArray =
        when {
            !device.isExternal -> IntArray(0)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                device.vibratorManager.vibratorIds
                    .take(2)
                    .toIntArray()
            else -> IntArray(0)
        }

    @Suppress("DEPRECATION")
    private val legacy: Vibrator? =
        if (device.isExternal && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            device.vibrator.takeIf { it.hasVibrator() }
        } else {
            null
        }

    override val count: Int = if (legacy != null) 1 else ids.size

    override fun set(
        large: Float,
        small: Float,
    ) {
        if (legacy != null) {
            legacy.vibrate(oneShot(large, legacy.hasAmplitudeControl()))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ids.isEmpty()) return
        val manager = device.vibratorManager
        val combined = CombinedVibration.startParallel()
        ids.forEachIndexed { i, id ->
            val level = if (i == 0) large else small
            if (level > 0f) combined.addVibrator(id, oneShot(level, manager.getVibrator(id).hasAmplitudeControl()))
        }
        // Only the small motor's level left: the large one stops as the combination replaces what played.
        if (large <= 0f && (ids.size < 2 || small <= 0f)) return off()
        manager.vibrate(combined.combine())
    }

    override fun off() {
        if (legacy != null) {
            legacy.cancel()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ids.isNotEmpty()) {
            device.vibratorManager.cancel()
        }
    }

    private fun oneShot(
        level: Float,
        control: Boolean,
    ) = VibrationEffect.createOneShot(RumbleLevels.MS, RumbleLevels.amplitude(level, control))
}
