// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The core capturing the next input from one controller, for the remap screen. Each test runs in its own process. */
@RunWith(AndroidJUnit4::class)
class BindingCaptureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "binding-capture")
    private val dataRoot = File(root, "data")
    private val biosDir = File(root, "bios")
    private val captured = LinkedBlockingQueue<String>()

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
        NativeLibrary.setCallbacks(
            object : NativeCallbacks {
                override fun onGameStarted(title: String) {}

                override fun onGameStopped() {}

                override fun onError(message: String) {}

                override fun onBindingCaptured(binding: String) {
                    captured.put(binding)
                }
            },
        )
        val resources = Resources.extract(context)
        assertTrue(NativeLibrary.initialize(dataRoot.absolutePath, resources.absolutePath, biosDir.absolutePath, INSTALL_ID))
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
        NativeLibrary.startBindingCapture("pad-a")
        NativeLibrary.onKey(DEVICE_ID, 99, true) // KEYCODE_BUTTON_X
        NativeLibrary.onKey(DEVICE_ID, 99, false)

        assertEquals("Button99", captured.poll(2, TimeUnit.SECONDS))

        NativeLibrary.startBindingCapture("pad-a")
        NativeLibrary.cancelBindingCapture()
        NativeLibrary.onKey(DEVICE_ID, 100, true) // KEYCODE_BUTTON_Y
        NativeLibrary.onKey(DEVICE_ID, 100, false)

        assertNull(captured.poll(500, TimeUnit.MILLISECONDS))
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
        NativeLibrary.startBindingCapture("pad-a")
        NativeLibrary.startBindingCapture("pad-a")
        NativeLibrary.onKey(DEVICE_ID, 96, true) // KEYCODE_BUTTON_A

        assertEquals("Button96", captured.poll(2, TimeUnit.SECONDS))
        NativeLibrary.onKey(DEVICE_ID, 97, true)
        assertNull(captured.poll(500, TimeUnit.MILLISECONDS))

        NativeLibrary.startBindingCapture("never-connected")
        NativeLibrary.onKey(DEVICE_ID, 98, true)
        assertNull(captured.poll(500, TimeUnit.MILLISECONDS))
    }

    // The trigger's key and two axes all follow it (PadState), and the first to cross wins.
    @Test
    fun capturesATrigger() {
        NativeLibrary.startBindingCapture("pad-a")
        NativeLibrary.onAxis(DEVICE_ID, 23, 0.0f) // AXIS_BRAKE
        NativeLibrary.onAxis(DEVICE_ID, 23, 1.0f)

        val binding = captured.poll(2, TimeUnit.SECONDS)
        assertTrue("binding $binding", binding in setOf("+Axis17", "+Axis23", "Button104"))
    }

    private companion object {
        const val INSTALL_ID = "test-install"
        const val DEVICE_ID = 1
    }
}
