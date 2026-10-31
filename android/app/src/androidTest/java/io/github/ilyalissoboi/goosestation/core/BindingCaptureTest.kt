// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.input.AxisMemory
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The core capturing the next input from one controller, for the remap screen. Each test runs in its own process. */
@RunWith(AndroidJUnit4::class)
class BindingCaptureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "binding-capture")
    private val dataRoot = File(root, "data")
    private val biosDir = File(root, "bios")
    private val captured = LinkedBlockingQueue<String>()

    // On the core's thread.
    @Volatile private var onCaptured: (String) -> Unit = { captured.put(it) }

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
        NativeLibrary.setCallbacks(
            object : NativeCallbacks {
                override fun onGameStarted(
                    title: String,
                    serial: String,
                ) {}

                override fun onGameStopped() {}

                override fun onError(message: String) {}

                override fun onBindingCaptured(binding: String) {
                    onCaptured(binding)
                }
            },
        )
        val resources = Resources.extract(context)
        assertTrue(
            NativeLibrary.initialize(dataRoot.absolutePath, resources.absolutePath, biosDir.absolutePath, INSTALL_ID),
        )
        NativeLibrary.onDeviceConnected(DEVICE_ID, "Pad A", "pad-a")

        // The connection is queued: wait until the core has pad-a in its table, so a capture can find it.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (NativeLibrary.getPadBindings("pad-a") == null) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for pad-a to connect")
            Thread.sleep(50)
        }
    }

    @After
    fun tearDown() {
        NativeLibrary.cancelBindingCapture()
        NativeLibrary.setCallbacks(null)
    }

    @Test
    fun capturesAButtonUntilCancelled() {
        NativeLibrary.startBindingCapture("pad-a", false)
        NativeLibrary.onKey(DEVICE_ID, 99, true) // KEYCODE_BUTTON_X
        NativeLibrary.onKey(DEVICE_ID, 99, false)

        assertEquals("Button99", captured.poll(2, TimeUnit.SECONDS))

        NativeLibrary.startBindingCapture("pad-a", false)
        NativeLibrary.cancelBindingCapture()
        NativeLibrary.onKey(DEVICE_ID, 100, true) // KEYCODE_BUTTON_Y
        NativeLibrary.onKey(DEVICE_ID, 100, false)

        assertNull(captured.poll(500, TimeUnit.MILLISECONDS))
    }

    // A hotkey's capture: Select held, then a button, sent together as SelectChord sends them (onKeys).
    @Test
    fun aChordCaptureTakesTheButtonNotSelect() {
        NativeLibrary.startBindingCapture("pad-a", true)
        NativeLibrary.onKeys(DEVICE_ID, intArrayOf(109, 99), booleanArrayOf(true, true)) // SELECT, then X

        assertEquals("Button99", captured.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun aChordCaptureSkipsStart() {
        NativeLibrary.startBindingCapture("pad-a", true)
        NativeLibrary.onKey(DEVICE_ID, 108, true) // KEYCODE_BUTTON_START
        NativeLibrary.onKey(DEVICE_ID, 100, true) // KEYCODE_BUTTON_Y

        assertEquals("Button100", captured.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun cancelWithNoCaptureDoesNothing() {
        NativeLibrary.cancelBindingCapture()
        NativeLibrary.onKey(DEVICE_ID, 99, true)

        assertNull(captured.poll(500, TimeUnit.MILLISECONDS))
    }

    // A second capture replaces the first, and one for a controller never connected does nothing.
    @Test
    fun secondCaptureReplacesTheFirst() {
        NativeLibrary.startBindingCapture("pad-a", false)
        NativeLibrary.startBindingCapture("pad-a", false)
        NativeLibrary.onKey(DEVICE_ID, 96, true) // KEYCODE_BUTTON_A

        assertEquals("Button96", captured.poll(2, TimeUnit.SECONDS))
        NativeLibrary.onKey(DEVICE_ID, 97, true)
        assertNull(captured.poll(500, TimeUnit.MILLISECONDS))

        NativeLibrary.startBindingCapture("never-connected", false)
        NativeLibrary.onKey(DEVICE_ID, 98, true)
        assertNull(captured.poll(500, TimeUnit.MILLISECONDS))
    }

    // The trigger's key and two axes all follow it (PadState), and the first to cross wins.
    @Test
    fun capturesATrigger() {
        NativeLibrary.startBindingCapture("pad-a", false)
        NativeLibrary.onAxis(DEVICE_ID, 23, 0.0f) // AXIS_BRAKE
        NativeLibrary.onAxis(DEVICE_ID, 23, 1.0f)

        val binding = captured.poll(2, TimeUnit.SECONDS)
        assertTrue("binding $binding", binding in setOf("+Axis17", "+Axis23", "Button104"))
    }

    // Ruling 29: a stick the pad reported at rest before the capture began is taken on its first push, a flick; one
    // never reported must still come back first. The start takes in the input sent before it.
    @Test
    fun stickAtRestBeforeTheCaptureIsTakenAtOnce() {
        NativeLibrary.onAxis(DEVICE_ID, 0, 0.1f) // AXIS_X
        NativeLibrary.startBindingCapture("pad-a", false)
        NativeLibrary.onAxis(DEVICE_ID, 0, 0.8f)

        assertEquals("+Axis0", captured.poll(2, TimeUnit.SECONDS))
    }

    // Ruling 30: the app sends a pad's current axes, remembered while they weren't forwarded, just before the start.
    // The capture takes them in, so the stick's first push is taken.
    @Test
    fun axesSentJustBeforeTheStartSeedIt() {
        val axes = intArrayOf(0, 1) // AXIS_X, AXIS_Y
        val memory = AxisMemory(axes)
        memory.record(DEVICE_ID) { 0f }
        val latest = memory.latest(DEVICE_ID)!!
        for ((index, value) in latest.withIndex()) NativeLibrary.onAxis(DEVICE_ID, axes[index], value)
        NativeLibrary.startBindingCapture("pad-a", false)
        NativeLibrary.onAxis(DEVICE_ID, 0, 0.8f)

        assertEquals("+Axis0", captured.poll(2, TimeUnit.SECONDS))
    }

    // The start takes in the input queued before it, even when it runs before the core's thread has polled that input.
    // onBindingCaptured runs on that thread, in its queued work, which comes before the input in each turn of its loop:
    // an axis queued from there, then a start (which runs there and then, on that thread), always share one turn.
    @Test
    fun startTakesInTheInputQueuedBeforeIt() {
        val nested = AtomicBoolean(false)
        val nestedStarted = CountDownLatch(1)
        onCaptured = { binding ->
            if (nested.compareAndSet(false, true)) {
                NativeLibrary.onAxis(DEVICE_ID, 0, 0.0f) // AXIS_X at rest: a first 0, never passed on as an event
                NativeLibrary.startBindingCapture("pad-a", false)
                nestedStarted.countDown()
            } else {
                captured.put(binding)
            }
        }
        NativeLibrary.startBindingCapture("pad-a", false)
        NativeLibrary.onKey(DEVICE_ID, 99, true)
        assertTrue(nestedStarted.await(2, TimeUnit.SECONDS))

        NativeLibrary.onAxis(DEVICE_ID, 0, 0.8f)

        assertEquals("+Axis0", captured.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun stickNeverReportedMustReturnFirst() {
        NativeLibrary.startBindingCapture("pad-a", false)
        NativeLibrary.onAxis(DEVICE_ID, 1, 0.7f) // AXIS_Y

        assertNull(captured.poll(500, TimeUnit.MILLISECONDS))
    }

    private companion object {
        const val INSTALL_ID = "test-install"
        const val DEVICE_ID = 1
    }
}
