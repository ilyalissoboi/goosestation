// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.graphics.SurfaceTexture
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** The in-game menu's core calls: save state times and pictures, saving and loading, and the analog mode. */
@RunWith(AndroidJUnit4::class)
class GameCallsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "game-calls")
    private val dataRoot = File(root, "data")
    private val biosDir = File(root, "bios")
    private val states = File(dataRoot, "savestates")

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
    }

    @Test
    fun noSavesReadsAsZeros() {
        initialize()

        assertEquals(List(11) { 0L }, NativeLibrary.getSaveStateTimes(SERIAL).toList())
    }

    @Test
    fun aStateFileIsSeen() {
        initialize()
        states.mkdirs()
        File(states, "${SERIAL}_3.sav").writeBytes(ByteArray(64) { 7 })
        File(states, "${SERIAL}_resume.sav").writeBytes(ByteArray(64) { 7 })

        val times = NativeLibrary.getSaveStateTimes(SERIAL)

        val now = System.currentTimeMillis() / 1000
        assertTrue("resume ${times[0]}", abs(times[0] - now) < 5)
        assertTrue("slot 3 ${times[3]}", abs(times[3] - now) < 5)
        val others = (1..10) - 3
        assertEquals(others.map { 0L }, others.map { times[it] })
    }

    @Test
    fun aDamagedStateHasNoPicture() {
        initialize()
        states.mkdirs()
        File(states, "${SERIAL}_3.sav").writeBytes(ByteArray(64) { 7 })

        assertNull(NativeLibrary.getSaveStateScreenshot(SERIAL, 3))
        assertNull(NativeLibrary.getSaveStateScreenshot(SERIAL, 4))
    }

    @Test
    fun withoutAGameTheCallsSaySo() {
        initialize()

        assertEquals("No game is running.", NativeLibrary.saveState(1))
        assertEquals("No game is running.", NativeLibrary.loadState(1))
        assertEquals(-1, NativeLibrary.getAnalogMode())
    }

    // Needs the emulator's /sdcard/GooseTest bios/ps-30j.bin and games/padtest.psexe (docs/android.md).
    @Test
    fun aSaveRoundTripsWhileAGameRuns() {
        val exe = File(root, "padtest.psexe")
        assumeTrue(
            "No BIOS or padtest in /sdcard/GooseTest",
            copyAsShell("$SDCARD_TESTS/bios/ps-30j.bin", File(biosDir, "ps-30j.bin")) &&
                copyAsShell("$SDCARD_TESTS/games/padtest.psexe", exe),
        )
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        NativeLibrary.setCallbacks(
            object : NativeCallbacks {
                override fun onGameStarted(
                    title: String,
                    serial: String,
                ) {
                    lastSerial = serial
                    started.countDown()
                }

                override fun onGameStopped() = stopped.countDown()

                override fun onError(message: String) {}
            },
        )
        initialize()
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val surface = Surface(texture)
        NativeLibrary.surfaceChanged(surface, 640, 480, 1f)
        try {
            NativeLibrary.startGame(exe.absolutePath, null)
            assertTrue("padtest booted", started.await(30, TimeUnit.SECONDS))
            assertEquals(PADTEST_SERIAL, lastSerial)
            // Let it draw a frame or two, so the state has a picture.
            Thread.sleep(1_000)
            NativeLibrary.setPaused(true)

            assertNull(NativeLibrary.saveState(2))

            assertTrue(File(states, "${PADTEST_SERIAL}_2.sav").length() > 0)
            assertTrue(NativeLibrary.getSaveStateTimes(PADTEST_SERIAL)[2] > 0)
            val picture = NativeLibrary.getSaveStateScreenshot(PADTEST_SERIAL, 2)
            assertNotNull("a picture", picture)
            picture!!
            assertEquals(picture.width * picture.height, picture.pixels.size)
            assertTrue("opaque", picture.pixels.all { it ushr 24 == 0xFF })
            assertNull(NativeLibrary.loadState(2))
            assertTrue(NativeLibrary.loadState(5)!!.isNotEmpty())
            assertTrue(NativeLibrary.getAnalogMode() >= 0)
        } finally {
            NativeLibrary.shutdownGame()
            assertTrue("padtest stopped", stopped.await(30, TimeUnit.SECONDS))
            NativeLibrary.surfaceChanged(null, 0, 0, 1f)
            NativeLibrary.setCallbacks(null)
            surface.release()
            texture.release()
        }
        // No resume state: the shutdown was told not to save one.
        assertEquals(0L, NativeLibrary.getSaveStateTimes(PADTEST_SERIAL)[0])
    }

    // The pad's Analog button on X alone, and fast forward on Select + X. padtest locks the controller in analog mode,
    // so each press of the game's Analog button posts "… is locked to analog mode by the game": a press the game got.
    @Test
    fun aChordDoesntPressItsButtonForTheGame() {
        val fastForward = LinkedBlockingQueue<Boolean>()
        val analogPresses = AtomicInteger()
        withPadtest(onFastForward = { fastForward.put(it) }, onAnalogPress = { analogPresses.incrementAndGet() }) {
            NativeLibrary.setPadBinding(PAD, "Analog", "Button99")
            NativeLibrary.setHotkeyBinding(PAD, "ToggleFastForward", "Button99")
            Thread.sleep(500)
            analogPresses.set(0)

            NativeLibrary.onKeys(DEVICE_ID, intArrayOf(SELECT, X), booleanArrayOf(true, true))
            Thread.sleep(300)
            NativeLibrary.onKeys(DEVICE_ID, intArrayOf(X, SELECT), booleanArrayOf(false, false))

            assertEquals("the hotkey fired", true, fastForward.poll(3, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertEquals("the game's Analog button wasn't pressed", 0, analogPresses.get())
        }
    }

    // The analog mode hotkey presses the game's Analog button once per chord, and neither key alone does.
    @Test
    fun theAnalogModeHotkeyIsAChord() {
        val analogPresses = AtomicInteger()
        withPadtest(onAnalogPress = { analogPresses.incrementAndGet() }) {
            NativeLibrary.setHotkeyBinding(PAD, "ToggleAnalogMode", "Button99")
            Thread.sleep(500)
            analogPresses.set(0)

            NativeLibrary.onKey(DEVICE_ID, SELECT, true)
            Thread.sleep(200)
            NativeLibrary.onKey(DEVICE_ID, SELECT, false)
            NativeLibrary.onKey(DEVICE_ID, X, true)
            Thread.sleep(200)
            NativeLibrary.onKey(DEVICE_ID, X, false)
            Thread.sleep(500)
            assertEquals("Select or X alone", 0, analogPresses.get())

            NativeLibrary.onKeys(DEVICE_ID, intArrayOf(SELECT, X), booleanArrayOf(true, true))
            Thread.sleep(300)
            NativeLibrary.onKeys(DEVICE_ID, intArrayOf(X, SELECT), booleanArrayOf(false, false))
            Thread.sleep(500)
            assertEquals("Select + X", 1, analogPresses.get())
        }
    }

    // The control: without Select, X is the pad's Analog button.
    @Test
    fun aButtonAloneStillPressesItsBinding() {
        val analogPresses = AtomicInteger()
        withPadtest(onAnalogPress = { analogPresses.incrementAndGet() }) {
            NativeLibrary.setPadBinding(PAD, "Analog", "Button99")
            NativeLibrary.setHotkeyBinding(PAD, "ToggleFastForward", "Button99")
            Thread.sleep(500)
            analogPresses.set(0)

            NativeLibrary.onKey(DEVICE_ID, X, true)
            Thread.sleep(300)
            NativeLibrary.onKey(DEVICE_ID, X, false)
            Thread.sleep(500)

            assertEquals(1, analogPresses.get())
        }
    }

    /** Boots padtest with a pad connected, runs [body], and shuts it down; skipped without the files. */
    private fun withPadtest(
        onFastForward: (Boolean) -> Unit = {},
        onAnalogPress: () -> Unit = {},
        body: () -> Unit,
    ) {
        val exe = File(root, "padtest.psexe")
        assumeTrue(
            "No BIOS or padtest in /sdcard/GooseTest",
            copyAsShell("$SDCARD_TESTS/bios/ps-30j.bin", File(biosDir, "ps-30j.bin")) &&
                copyAsShell("$SDCARD_TESTS/games/padtest.psexe", exe),
        )
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        NativeLibrary.setCallbacks(
            object : NativeCallbacks {
                override fun onGameStarted(
                    title: String,
                    serial: String,
                ) = started.countDown()

                override fun onGameStopped() = stopped.countDown()

                override fun onError(message: String) {}

                override fun onFastForwardChanged(enabled: Boolean) = onFastForward(enabled)

                override fun onOsdMessage(
                    key: String,
                    text: String,
                    seconds: Float,
                    icon: ArgbImage?,
                ) {
                    if (key == "Controller0AnalogMode" && "locked" in text) onAnalogPress()
                }
            },
        )
        initialize()
        NativeLibrary.onDeviceConnected(DEVICE_ID, "Pad A", PAD)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (NativeLibrary.getPadBindings(PAD) == null) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for the pad to connect")
            Thread.sleep(50)
        }
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val surface = Surface(texture)
        NativeLibrary.surfaceChanged(surface, 640, 480, 1f)
        try {
            NativeLibrary.startGame(exe.absolutePath, null)
            assertTrue("padtest booted", started.await(30, TimeUnit.SECONDS))
            Thread.sleep(1_000)
            body()
        } finally {
            NativeLibrary.shutdownGame()
            assertTrue("padtest stopped", stopped.await(30, TimeUnit.SECONDS))
            NativeLibrary.surfaceChanged(null, 0, 0, 1f)
            NativeLibrary.setCallbacks(null)
            surface.release()
            texture.release()
        }
    }

    @Volatile private var lastSerial = ""

    private fun initialize() {
        val resources = Resources.extract(context)
        assertTrue(
            NativeLibrary.initialize(dataRoot.absolutePath, resources.absolutePath, biosDir.absolutePath, INSTALL_ID),
        )
    }

    // The test app can't read /sdcard; the shell can. False if there's nothing there.
    private fun copyAsShell(
        path: String,
        destination: File,
    ): Boolean {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        destination.parentFile!!.mkdirs()
        ParcelFileDescriptor.AutoCloseInputStream(output).use { input ->
            destination.outputStream().use { input.copyTo(it) }
        }
        return destination.length() > 0
    }

    private companion object {
        const val INSTALL_ID = "test-install"
        const val SERIAL = "SLUS-00000"
        const val SDCARD_TESTS = "/sdcard/GooseTest"
        const val PADTEST_SERIAL = "HASH-9FDA0E1A56D8DF5B"
        const val PAD = "pad-a"
        const val DEVICE_ID = 1
        const val SELECT = 109
        const val X = 99
    }
}
