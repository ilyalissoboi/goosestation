// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

/** A device as [AxisMemory.replay] sees it: its descriptor, and the last value sent to the core for each axis. */
interface SentAxes {
    val descriptor: String
    val axes: FloatArray
}

/**
 * Each controller's latest value for each of [axes], from every motion it sends, whether it went to the core or not.
 * A binding capture sends them to the core as it starts, so the core knows which axes are at rest.
 */
class AxisMemory(
    private val axes: IntArray,
) {
    private val values = HashMap<Int, FloatArray>()

    /** A motion from [deviceId]: [valueOf] gives each axis's value. */
    fun record(
        deviceId: Int,
        valueOf: (axis: Int) -> Float,
    ) {
        val latest = values.getOrPut(deviceId) { FloatArray(axes.size) }
        for ((index, axis) in axes.withIndex()) latest[index] = valueOf(axis)
    }

    /** A copy of [deviceId]'s latest values, in [axes]' order, or null if it hasn't moved: they aren't known. */
    fun latest(deviceId: Int): FloatArray? = values[deviceId]?.copyOf()

    /**
     * Gives [send] the latest values of each of [devices] (by device ID) with [descriptor] whose values are known, axis
     * by axis in [axes]' order, and keeps them as its [SentAxes.axes]. A device that hasn't moved gets nothing.
     */
    fun replay(
        descriptor: String,
        devices: Map<Int, SentAxes>,
        send: (deviceId: Int, axis: Int, value: Float) -> Unit,
    ) {
        for ((deviceId, device) in devices) {
            if (device.descriptor != descriptor) continue
            val latest = values[deviceId] ?: continue
            for ((index, axis) in axes.withIndex()) {
                device.axes[index] = latest[index]
                send(deviceId, axis, latest[index])
            }
        }
    }

    /** [deviceId] is gone. */
    fun forget(deviceId: Int) {
        values.remove(deviceId)
    }
}
