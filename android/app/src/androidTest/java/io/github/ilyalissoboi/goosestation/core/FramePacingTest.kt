// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import io.github.ilyalissoboi.goosestation.storage.TestTrees
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Frames locked to the screen's refreshes ([Main] SyncToHostRefreshRate with the Choreographer timeline): the game
 * runs at the screen's rate, each frame done by its refresh's deadline.
 *
 * Needs the emulator's /sdcard/GooseTest bios/ps-30j.bin, games/Vagrant Story (USA).chd and
 * savestates/SLUS-01040_2.sav (the command menu); without them the tests are skipped.
 */
@BootsGames
@LargeTest
@RunWith(AndroidJUnit4::class)
class FramePacingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "frame-pacing")
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
        assertTrue("Vagrant Story listed", NativeLibrary.getGameList().any { it.path == disc })
    }

    @After
    fun tearDown() {
        NativeLibrary.shutdownGame()
        NativeLibrary.setCallbacks(null)
        TestTrees.deleteAll(context)
    }

    // 10 s at the menu: every frame locked, one refresh each, nearly all done by their deadlines (planned from nine
    // frames in ten, the odd long one may not be).
    @Test
    fun aLockedGameMakesItsDeadlines() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                boot(frames, syncToHost = true)
                val before = NativeLibrary.getFramePacingStatsForTest()
                Thread.sleep(10_000)
                val after = NativeLibrary.getFramePacingStatsForTest()
                val locked = after[0] - before[0]
                val made = after[1] - before[1]
                assertEquals("one refresh a frame", 1L, after[2])
                assertTrue("$locked frames locked in 10 s", locked in 570..630)
                assertTrue("$made of $locked done by their deadlines", made >= locked * 85 / 100)
            }
        }

    // Where the platform reports presents back (Vulkan's VK_GOOGLE_display_timing, EGL's frame timestamps), their
    // feedback reaches the cutoff tracker for most frames, and the lock still makes its deadlines.
    @Test
    fun presentFeedbackReachesTheLock() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                boot(frames, syncToHost = true)
                val before = NativeLibrary.getFramePacingStatsForTest()
                Thread.sleep(10_000)
                val after = NativeLibrary.getFramePacingStatsForTest()
                val locked = after[0] - before[0]
                val reports = after[3] - before[3]
                // The emulators' Vulkan (gfxstream) gives no past presentation timings: checked on a device.
                if (reports == 0L) {
                    Log.i("FramePacingTest", "No present feedback on this device: nothing to check")
                    return@withSurface
                }
                assertTrue("$reports of $locked presents reported back", reports >= locked * 8 / 10)
                assertTrue(
                    "an offset within half a refresh before and 0.9 after: ${after[4]} ns",
                    after[4] in -9_000_000..15_000_000,
                )
                assertTrue(
                    "${after[1] - before[1]} of $locked done by their deadlines",
                    after[1] - before[1] >= locked * 9 / 10,
                )
            }
        }

    // The refresh timeline only runs while a game does, unpaused: nothing wakes 60 times a second in the library.
    @Test
    fun theTimelineRunsOnlyWhileAGameDoes() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                assertTimeline("before a game", false)
                boot(frames, syncToHost = true)
                assertTimeline("with a game", true)
                NativeLibrary.setPaused(true)
                assertTimeline("paused", false)
                NativeLibrary.setPaused(false)
                assertTimeline("resumed", true)
                stopped = CountDownLatch(1)
                NativeLibrary.shutdownGame()
                assertTrue("stopped", stopped.await(20, TimeUnit.SECONDS))
                assertTimeline("after the game", false)
            }
        }

    private fun assertTimeline(
        what: String,
        running: Boolean,
    ) {
        val until = System.nanoTime() + 1_000_000_000L
        while ((NativeLibrary.getVsyncTimelineForTest() != null) != running && System.nanoTime() < until) {
            Thread.sleep(20)
        }
        assertEquals("timeline $what", running, NativeLibrary.getVsyncTimelineForTest() != null)
    }

    // Switching apps over and over while locked: the surface goes mid-sleep and comes back; it never hangs, and the
    // lock picks up again.
    @Test
    fun aSurfaceGoneMidSleepDoesNotHang() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                boot(frames, syncToHost = true)
                repeat(20) { i ->
                    NativeLibrary.surfaceChanged(null, 0, 0, 1f, 0f)
                    Thread.sleep((i % 4).toLong() * 6)
                    NativeLibrary.surfaceChanged(frames.surface, 640, 480, 1f, 60f)
                    Thread.sleep((i % 3).toLong() * 9)
                }
                frames.nextFrame(30)
                assertLockedAgain()
            }
        }

    // Paused and resumed: the lock lets go and comes back, frames at the screen's rate again.
    @Test
    fun pausingAndResumingKeepsTheLock() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                boot(frames, syncToHost = true)
                NativeLibrary.setPaused(true)
                Thread.sleep(1_000)
                NativeLibrary.setPaused(false)
                assertLockedAgain()
            }
        }

    // Fast-forward lets go of the screen (and of vsync), and the lock comes back after.
    @Test
    fun fastForwardLetsGoAndComesBack() =
        FrameReader(640, 480).use { frames ->
            withSurface(frames) {
                boot(frames, syncToHost = true)
                NativeLibrary.setFastForward(true)
                Thread.sleep(1_000)
                assertEquals("unlocked while fast-forwarding", 0L, NativeLibrary.getFramePacingStatsForTest()[2])
                NativeLibrary.setFastForward(false)
                assertLockedAgain()
            }
        }

    // Within 2 s: locked again, one refresh a frame, presents coming at about the screen's rate.
    private fun assertLockedAgain() {
        val until = System.nanoTime() + 2_000_000_000L
        while (NativeLibrary.getFramePacingStatsForTest()[2] != 1L && System.nanoTime() < until) {
            Thread.sleep(20)
        }
        assertEquals("locked again", 1L, NativeLibrary.getFramePacingStatsForTest()[2])
        val before = NativeLibrary.getFramePacingStatsForTest()[0]
        Thread.sleep(2_000)
        val presents = NativeLibrary.getFramePacingStatsForTest()[0] - before
        assertTrue("$presents locked presents in 2 s", presents in 110..130)
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

    private fun boot(
        frames: FrameReader,
        syncToHost: Boolean,
    ) {
        NativeLibrary.setBoolSetting("Main", "SyncToHostRefreshRate", syncToHost)
        NativeLibrary.applySettings()
        TestRenderer.apply()
        started = CountDownLatch(1)
        NativeLibrary.startGame(disc, 1, false)
        assertTrue("Vagrant Story booted", started.await(60, TimeUnit.SECONDS))
        Thread.sleep(1_000)
        assertNull("the menu state loads", NativeLibrary.loadState(SERIAL, 2))
        Thread.sleep(2_000)
        frames.nextFrame(30)
    }

    companion object {
        const val SDCARD_TESTS = "/sdcard/GooseTest"
        const val SERIAL = "SLUS-01040"
        const val DISC = "Vagrant Story (USA).chd"
    }
}
