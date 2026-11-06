// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RumbleTest {
    private val calls = mutableListOf<String>()

    private inner class FakeMotors(
        private val id: Int,
        override val count: Int,
    ) : Motors {
        override fun set(
            large: Float,
            small: Float,
        ) {
            calls += "$id set $large $small"
        }

        override fun off() {
            calls += "$id off"
        }
    }

    private val motors = mutableMapOf(1 to FakeMotors(1, 2), 2 to FakeMotors(2, 2))
    private val rumble = Rumble(motorsFor = { motors[it] }, post = { it.run() })

    @Test
    fun levelsGoToTheDevicesMotors() {
        rumble.onRumble(1, 0.5f, 0.25f)
        assertEquals(listOf("1 set 0.5 0.25"), calls)
    }

    @Test
    fun zeroTurnsItOff() {
        rumble.onRumble(1, 0f, 0f)
        assertEquals(listOf("1 off"), calls)
    }

    @Test
    fun oneMotorTakesTheStronger() {
        motors[1] = FakeMotors(1, 1)
        rumble.onRumble(1, 0.2f, 0.7f)
        assertEquals(listOf("1 set 0.7 0.0"), calls)
    }

    @Test
    fun noMotorsNothing() {
        motors[1] = FakeMotors(1, 0)
        rumble.onRumble(1, 1f, 1f)
        rumble.onRumble(3, 1f, 1f) // gone, or never a controller
        assertTrue(calls.isEmpty())
    }

    @Test
    fun stopAllStopsEachStillOn() {
        rumble.onRumble(1, 1f, 0f)
        rumble.onRumble(2, 1f, 0f)
        rumble.onRumble(2, 0f, 0f)
        calls.clear()

        rumble.stopAll()
        rumble.stopAll()

        assertEquals(listOf("1 off"), calls)
    }

    @Test
    fun amplitudeIsNeverZeroWhileOn() {
        assertEquals(1, RumbleLevels.amplitude(0.001f, control = true))
        assertEquals(128, RumbleLevels.amplitude(0.5f, control = true))
        assertEquals(255, RumbleLevels.amplitude(1f, control = true))
        assertEquals(255, RumbleLevels.amplitude(3f, control = true))
    }

    // Without amplitude control, the vibrator's own strength.
    @Test
    fun noAmplitudeControlIsTheDefault() {
        assertEquals(RumbleLevels.DEFAULT, RumbleLevels.amplitude(0.3f, control = false))
    }
}
