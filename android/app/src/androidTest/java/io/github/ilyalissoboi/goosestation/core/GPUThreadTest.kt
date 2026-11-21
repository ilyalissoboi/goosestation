// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import io.github.ilyalissoboi.goosestation.storage.TestTrees
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The GPU thread ([GPU] UseGPUThread) against the surface's life: the pictures it draws are the ones drawn without it,
 * and a surface destroyed, given back, resized, a pause, a renderer or a switch of its own mid-game, a state across
 * the switch and a stop under load all leave the game drawing, never crashed or hung.
 *
 * Needs the emulator's /sdcard/GooseTest bios/ps-30j.bin, games/Vagrant Story (USA).chd and
 * savestates/SLUS-01040_2.sav (the command menu, a still picture but for its cursor); without them the tests are skipped.
 */
@BootsGames
@LargeTest
@RunWith(AndroidJUnit4::class)
class GPUThreadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "gpu-thread")
    private val dataRoot = File(root, "data")
    private val biosDir = File(root, "bios")
    private var disc = ""
    private var started = CountDownLatch(1)
    private var stopped = CountDownLatch(1)

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
        val games = TestTrees.create(context, emptyMap())
        assumeTrue(
            "No BIOS, Vagrant Story or its menu state in /sdcard/GooseTest",
            copyFromShell("$SDCARD_TESTS/bios/ps-30j.bin", File(biosDir, "ps-30j.bin")) &&
                copyFromShell("$SDCARD_TESTS/games/Vagrant*.chd", File(TestTrees.directory(context, games), DISC)) &&
                copyFromShell(
                    "$SDCARD_TESTS/savestates/SLUS-01040_2.sav",
                    File(dataRoot, "savestates/SLUS-01040_2.sav"),
                ),
        )
        disc = "$games/$DISC"
        NativeLibrary.setCallbacks(
            object : NativeCallbacks {
                override fun onGameStarted(
                    title: String,
                    serial: String,
                    startPaused: Boolean,
                    pauseOnDisconnect: Boolean,
                ) = started.countDown()

                override fun onGameStopped() = stopped.countDown()

                override fun onError(message: String) {}
            },
        )
        initializeCore(context, dataRoot, biosDir, File(root, "offline"))
        NativeLibrary.setGameFolders(arrayOf(games))
        NativeLibrary.refreshGameList(true)
        // Listed, as the app lists the library before a start: the state's disc is found among the listed discs.
        assertTrue("Vagrant Story listed", NativeLibrary.getGameList().any { it.path == disc })
        NativeLibrary.setIntSetting("GPU", "ResolutionScale", 2)
    }

    @After
    fun tearDown() {
        NativeLibrary.shutdownGame()
        NativeLibrary.setCallbacks(null)
        TestTrees.deleteAll(context)
    }

    // The menu as drawn with the thread off and on: the same picture.
    @Test
    fun theThreadDrawsTheSameFrames() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                val off = boot(frames, gpuThread = false)
                NativeLibrary.shutdownGame()
                assertTrue("stopped", stopped.await(20, TimeUnit.SECONDS))
                val on = boot(frames, gpuThread = true)
                assertSamePicture(off, on)
            }
        }

    // Switching apps: the surface destroyed and given back, over and over, while the game draws.
    @Test
    fun switchingAppsMidFrameKeepsDrawing() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                val before = boot(frames, gpuThread = true)
                repeat(20) {
                    NativeLibrary.surfaceChanged(null, 0, 0, 1f, 0f)
                    NativeLibrary.surfaceChanged(frames.surface, 640, 480, 1f, 60f)
                }
                assertSamePicture(before, frames.nextFrame(30))
            }
        }

    // A rotation: the same surface at another size, back and forth.
    @Test
    fun resizingTheSurfaceKeepsDrawing() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                boot(frames, gpuThread = true)
                repeat(10) { i ->
                    if (i % 2 == 0) {
                        NativeLibrary.surfaceChanged(frames.surface, 640, 360, 1f, 60f)
                    } else {
                        NativeLibrary.surfaceChanged(frames.surface, 640, 480, 1f, 60f)
                    }
                }
                NativeLibrary.surfaceChanged(frames.surface, 640, 480, 1f, 60f)
                frames.nextFrame(10)
            }
        }

    // Optimal frame pacing on Vulkan: each present begins, the throttle sleeps, then it's submitted. The surface taken
    // away, given back or resized in between leaves the game drawing.
    @Test
    fun surfaceChangesBetweenAPresentAndItsSubmitKeepDrawing() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                NativeLibrary.setStringSetting("GPU", "Renderer", "Vulkan")
                NativeLibrary.setBoolSetting("Display", "OptimalFramePacing", true)
                val before = boot(frames, gpuThread = true)
                repeat(20) { i ->
                    NativeLibrary.surfaceChanged(null, 0, 0, 1f, 0f)
                    Thread.sleep((i % 4).toLong() * 5)
                    NativeLibrary.surfaceChanged(frames.surface, 640, 480, 1f, 60f)
                    Thread.sleep((i % 3).toLong() * 7)
                    NativeLibrary.surfaceChanged(frames.surface, 640, 360, 1f, 60f)
                }
                NativeLibrary.surfaceChanged(frames.surface, 640, 480, 1f, 60f)
                assertSamePicture(before, frames.nextFrame(30))
            }
        }

    // Paused, the frame stays what it was; resumed, frames come again.
    @Test
    fun pausingAndResumingKeepsTheFrame() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                val before = boot(frames, gpuThread = true)
                NativeLibrary.setPaused(true)
                NativeLibrary.surfaceChanged(null, 0, 0, 1f, 0f)
                NativeLibrary.surfaceChanged(frames.surface, 640, 480, 1f, 60f)
                NativeLibrary.setPaused(false)
                assertSamePicture(before, frames.nextFrame(30))
            }
        }

    // The software renderer and back mid-game, the device made again on the GPU thread each time. The hardware one is
    // the run's (OpenGL ES in the full tier's part on SwiftShader: the host's GPU here has no OpenGL ES 3.1).
    @Test
    fun switchingRendererWithTheThreadOnKeepsDrawing() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                boot(frames, gpuThread = true)
                val hardware = InstrumentationRegistry.getArguments().getString(TestRenderer.ARGUMENT) ?: "Vulkan"
                for (renderer in listOf("Software", hardware, "Software", hardware)) {
                    NativeLibrary.setStringSetting("GPU", "Renderer", renderer)
                    NativeLibrary.applySettings()
                    frames.nextFrame(30)
                }
            }
        }

    // The switch itself, off and on and off mid-game: the device moves between threads, the picture stays.
    @Test
    fun flippingTheSwitchMidGameKeepsTheFrame() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                val before = boot(frames, gpuThread = false)
                for (gpuThread in listOf(true, false)) {
                    NativeLibrary.setBoolSetting("GPU", "UseGPUThread", gpuThread)
                    NativeLibrary.applySettings()
                    assertSamePicture(before, frames.nextFrame(30))
                }
            }
        }

    // A state saved with the thread on loads with it off, to the same picture.
    @Test
    fun aStateSavedWithTheThreadLoadsWithoutIt() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                val before = boot(frames, gpuThread = true)
                assertNull("saved", NativeLibrary.saveState(SERIAL, 3))
                NativeLibrary.setBoolSetting("GPU", "UseGPUThread", false)
                NativeLibrary.applySettings()
                assertNull("loaded", NativeLibrary.loadState(SERIAL, 3))
                Thread.sleep(2_000)
                assertSamePicture(before, frames.nextFrame(30))
            }
        }

    // Stopped under load (8x, 4x MSAA): it stops, it doesn't hang.
    @Test
    fun stoppingWhilePresentingDoesNotHang() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                NativeLibrary.setIntSetting("GPU", "ResolutionScale", 8)
                NativeLibrary.setIntSetting("GPU", "Multisamples", 4)
                boot(frames, gpuThread = true)
                stopped = CountDownLatch(1)
                NativeLibrary.shutdownGame()
                assertTrue("stopped within 5 s", stopped.await(5, TimeUnit.SECONDS))
            }
        }

    private fun withSurface(
        frames: FrameReader,
        body: () -> Unit,
    ) {
        NativeLibrary.surfaceChanged(frames.surface, 640, 480, 1f, 60f)
        try {
            body()
        } finally {
            NativeLibrary.surfaceChanged(null, 0, 0, 1f, 0f)
        }
    }

    // Boots the menu state with the GPU thread on or off, and returns a frame once it has settled.
    private fun boot(
        frames: FrameReader,
        gpuThread: Boolean,
    ): IntArray {
        NativeLibrary.setBoolSetting("GPU", "UseGPUThread", gpuThread)
        NativeLibrary.applySettings()
        TestRenderer.apply()
        started = CountDownLatch(1)
        stopped = CountDownLatch(1)
        NativeLibrary.startGame(disc, 1, false)
        assertTrue("Vagrant Story booted", started.await(60, TimeUnit.SECONDS))
        Thread.sleep(1_000)
        assertNull("the menu state loads", NativeLibrary.loadState(SERIAL, 2))
        Thread.sleep(3_000)
        return frames.nextFrame(30)
    }

    private fun assertSamePicture(
        expected: IntArray,
        actual: IntArray,
    ) {
        val differing = FrameReader.differing(withoutTheCursor(expected), withoutTheCursor(actual))
        assertTrue("$differing of the pixels differ", differing < 0.001)
    }

    // The menu's cursor bobs, wherever the game has got to when a frame is read: its corner of the picture is left out.
    private fun withoutTheCursor(frame: IntArray): IntArray =
        frame.copyOf().also {
            for (y in CURSOR_TOP until CURSOR_BOTTOM) it.fill(0, y * WIDTH + CURSOR_LEFT, y * WIDTH + CURSOR_RIGHT)
        }

    companion object {
        const val SDCARD_TESTS = "/sdcard/GooseTest"
        const val SERIAL = "SLUS-01040"
        const val DISC = "Vagrant Story (USA).chd"
        const val WIDTH = 640
        const val CURSOR_LEFT = 340
        const val CURSOR_RIGHT = 400
        const val CURSOR_TOP = 180
        const val CURSOR_BOTTOM = 240
    }
}
