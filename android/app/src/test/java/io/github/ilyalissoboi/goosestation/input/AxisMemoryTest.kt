// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Each controller's latest axis values, from every motion it sends, so they can be sent to the core again. */
class AxisMemoryTest {
    private companion object {
        const val PAD = 7
        const val OTHER_PAD = 8
        const val X = 0
        const val Y = 1
        const val Z = 11
    }

    private val memory = AxisMemory(intArrayOf(X, Y, Z))

    @Test
    fun aPadThatHasntMovedHasNoValues() {
        assertNull(memory.latest(PAD))
    }

    @Test
    fun keepsTheLatestMotionsValuesInTheAxesOrder() {
        memory.record(PAD) { mapOf(X to 0.5f, Y to 0f, Z to -1f).getValue(it) }
        memory.record(PAD) { mapOf(X to 0.1f, Y to 0f, Z to -1f).getValue(it) }

        assertArrayEquals(floatArrayOf(0.1f, 0f, -1f), memory.latest(PAD), 0f)
    }

    @Test
    fun eachPadKeepsItsOwn() {
        memory.record(PAD) { 0.2f }
        memory.record(OTHER_PAD) { 0.9f }

        assertArrayEquals(floatArrayOf(0.2f, 0.2f, 0.2f), memory.latest(PAD), 0f)
        assertArrayEquals(floatArrayOf(0.9f, 0.9f, 0.9f), memory.latest(OTHER_PAD), 0f)
    }

    // The caller can't change what's kept through the array it got.
    @Test
    fun latestIsACopy() {
        memory.record(PAD) { 0.2f }
        memory.latest(PAD)!!.fill(1f)

        assertArrayEquals(floatArrayOf(0.2f, 0.2f, 0.2f), memory.latest(PAD), 0f)
    }

    @Test
    fun aRemovedPadIsForgotten() {
        memory.record(PAD) { 0.2f }
        memory.forget(PAD)

        assertNull(memory.latest(PAD))
    }

    private class Pad(
        override val descriptor: String,
        override val axes: FloatArray,
    ) : SentAxes

    // sendAxes(): every device of the descriptor whose values are known, axis by axis, with what was sent kept.
    @Test
    fun replaySendsTheDescriptorsKnownDevices() {
        val pads =
            linkedMapOf(
                PAD to Pad("pad-a", FloatArray(3)),
                OTHER_PAD to Pad("pad-b", FloatArray(3)),
                9 to Pad("pad-a", FloatArray(3)), // hasn't moved
            )
        memory.record(PAD) { mapOf(X to 0f, Y to 0.1f, Z to -1f).getValue(it) }
        memory.record(OTHER_PAD) { 0.5f }
        val sent = mutableListOf<Triple<Int, Int, Float>>()

        memory.replay("pad-a", pads) { deviceId, axis, value -> sent.add(Triple(deviceId, axis, value)) }

        assertEquals(listOf(Triple(PAD, X, 0f), Triple(PAD, Y, 0.1f), Triple(PAD, Z, -1f)), sent)
        assertArrayEquals(floatArrayOf(0f, 0.1f, -1f), pads.getValue(PAD).axes, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), pads.getValue(OTHER_PAD).axes, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), pads.getValue(9).axes, 0f)
    }
}
