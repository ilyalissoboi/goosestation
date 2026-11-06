// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/** A [surface] for the core to draw on whose frames a test can read: [nextFrame] waits for one drawn after the call. */
class FrameReader(
    val width: Int,
    val height: Int,
) : AutoCloseable {
    private val thread = HandlerThread("FrameReader").apply { start() }
    private val reader =
        ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            3,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN,
        )
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private var count = 0
    private var latest = IntArray(0)

    val surface: Surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val plane = image.planes[0]
            // Each pixel's R, G, B and A bytes, as 0xAABBGGRR.
            val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val pixels = IntArray(width * height)
            val row = IntArray(width)
            for (y in 0 until height) {
                buffer.position(y * plane.rowStride)
                buffer.asIntBuffer().get(row)
                row.copyInto(pixels, y * width)
            }
            image.close()
            lock.withLock {
                latest = pixels
                count++
                arrived.signalAll()
            }
        }, Handler(thread.looper))
    }

    /** The pixels of the [skip]+1'th frame drawn after the call, so the ones in flight before it are left out. */
    fun nextFrame(skip: Int = 5): IntArray =
        lock.withLock {
            val wanted = count + skip + 1
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (count < wanted) {
                val left = deadline - System.nanoTime()
                if (left <= 0) throw AssertionError("Timed out waiting for a frame")
                arrived.awaitNanos(left)
            }
            latest
        }

    override fun close() {
        reader.close()
        thread.quitSafely()
    }

    companion object {
        /** The share of pixels in which a colour channel of [a] and [b] differs by more than a fifth. */
        fun differing(
            a: IntArray,
            b: IntArray,
        ): Double {
            require(a.size == b.size)
            val count =
                a.indices.count { i ->
                    (0 until 24 step 8).any { shift -> abs((a[i] ushr shift and 0xFF) - (b[i] ushr shift and 0xFF)) > 48 }
                }
            return count.toDouble() / a.size
        }
    }
}
