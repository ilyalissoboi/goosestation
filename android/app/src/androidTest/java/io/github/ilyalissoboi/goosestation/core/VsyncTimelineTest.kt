// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen's refresh timeline the core paces frames to (Choreographer, through the native vsync thread): it ticks
 * at the screen's period, moves on, and reports nothing once stopped.
 */
@RunWith(AndroidJUnit4::class)
class VsyncTimelineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() = NativeLibrary.stopVsyncTimelineForTest()

    @Test
    fun theTimelineTicksAtTheScreensPeriod() {
        NativeLibrary.startVsyncTimelineForTest()
        val first = waitForTimeline(200)
        assertNotNull("a timeline within 200 ms", first)

        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val period = 1e9 / display.refreshRate
        assertEquals("the screen's period", period, first!![1].toDouble(), period * 0.02)
        assertTrue("a deadline before its refresh", first[2] in 1..first[1])

        Thread.sleep(500)
        val later = NativeLibrary.getVsyncTimelineForTest()
        assertNotNull(later)
        assertTrue("refreshes move on", later!![0] > first[0])

        NativeLibrary.stopVsyncTimelineForTest()
        Thread.sleep(150)
        assertNull("nothing once stopped", NativeLibrary.getVsyncTimelineForTest())
    }

    private fun waitForTimeline(ms: Long): LongArray? {
        val until = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < until) {
            NativeLibrary.getVsyncTimelineForTest()?.let { return it }
            Thread.sleep(5)
        }
        return null
    }
}
