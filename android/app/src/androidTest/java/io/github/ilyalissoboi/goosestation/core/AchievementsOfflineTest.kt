// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.graphics.SurfaceTexture
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.core.FakeRetroAchievements.Companion.ACHIEVEMENT_ID
import io.github.ilyalissoboi.goosestation.core.FakeRetroAchievements.Companion.ACHIEVEMENT_TITLE
import io.github.ilyalissoboi.goosestation.core.FakeRetroAchievements.Companion.GAME_HASH
import io.github.ilyalissoboi.goosestation.core.FakeRetroAchievements.Companion.LEADERBOARD_ID
import io.github.ilyalissoboi.goosestation.core.FakeRetroAchievements.Companion.TOKEN
import io.github.ilyalissoboi.goosestation.core.FakeRetroAchievements.Companion.USER
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * RetroAchievements without a connection, against [FakeRetroAchievements]: what's kept on disk, sent and answered.
 * Each test boots padtest: the PS-EXE, with the fake's hash, or its disc image, which keeps hardcore (a PS-EXE boot
 * turns it off). Skipped without the emulator's /sdcard/GooseTest bios/ps-30j.bin and games/padtest.psexe and
 * "Pad Test #1 (100%).cue" and ".bin" (docs/android.md).
 */
@RunWith(AndroidJUnit4::class)
class AchievementsOfflineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "achievements-offline")
    private val dataRoot = File(root, "data")
    private val biosDir = File(root, "bios")
    private val folder = File(root, "offline")
    private val exe = File(root, "padtest.psexe")
    private val disc = File(root, "$PAD_TEST_DISC.cue")
    private val server = FakeRetroAchievements()

    private val started = CountDownLatch(1)
    private val stopped = CountDownLatch(1)

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
        NativeLibrary.setAchievementsServerForTest(server.url)
        NativeLibrary.setAchievementsGameHashForTest(GAME_HASH)
        NativeLibrary.setCallbacks(
            object : NativeCallbacks {
                override fun onGameStarted(
                    title: String,
                    serial: String,
                ) = started.countDown()

                override fun onGameStopped() = stopped.countDown()

                override fun onError(message: String) {}
            },
        )
        assumeTrue(
            "No BIOS or padtest in /sdcard/GooseTest",
            copyAsShell("$SDCARD_TESTS/bios/ps-30j.bin", File(biosDir, "ps-30j.bin")) &&
                copyAsShell("$SDCARD_TESTS/games/padtest.psexe", exe) &&
                copyAsShell("$SDCARD_TESTS/games/Pad*.cue", disc) &&
                copyAsShell("$SDCARD_TESTS/games/Pad*.bin", File(root, "$PAD_TEST_DISC.bin")),
        )
    }

    @After
    fun tearDown() {
        NativeLibrary.setCallbacks(null)
        NativeLibrary.setAchievementsGameHashForTest(null)
        NativeLibrary.setAchievementsServerForTest(null)
        server.close()
    }

    // Review Focus 1: the unlock is on disk before its send can fail, so an app killed now keeps it.
    @Test
    fun anUnreachableUnlockIsOnDisk() {
        initialize(SIGNED_IN_SETTINGS)
        playing {
            server.unreachable = true
            waitFor("the unlock on disk", 30) { queued().any { it.getInt("id") == ACHIEVEMENT_ID } }
        }

        val record = queued().single { it.getInt("id") == ACHIEVEMENT_ID }
        assertEquals("unlock", record.getString("kind"))
        assertEquals(USER, record.getString("user"))
        assertEquals(GAME_HASH, record.getString("hash"))
        assertFalse(record.getBoolean("hardcore"))
        assertEquals(ACHIEVEMENT_TITLE, record.getString("title"))
        assertNoToken()
    }

    @Test
    fun aDeliveredUnlockIsCleared() {
        initialize(SIGNED_IN_SETTINGS)
        playing {
            waitFor("the award", 30) { server.awards().isNotEmpty() }
            waitFor("the queue to empty") { File(folder, "queue.json").exists() && queued().isEmpty() }
        }

        val award = server.awards().single()
        assertEquals(ACHIEVEMENT_ID.toString(), award["a"])
        assertFalse("a first send isn't backdated", award.containsKey("o"))
    }

    @Test
    fun anEntryWhileUnreachableIsOnDisk() {
        initialize(SIGNED_IN_SETTINGS.replace("Enabled = true", "Enabled = true\nChallengeMode = true"))
        playing(disc) {
            assertTrue("hardcore is active", NativeLibrary.isHardcoreActive())
            server.unreachable = true
            waitFor("the entry on disk", 40) { queued().any { it.getString("kind") == "entry" } }
        }

        val entry = queued().single { it.getString("kind") == "entry" }
        assertEquals(LEADERBOARD_ID, entry.getInt("id"))
        assertEquals(USER, entry.getString("user"))
        assertTrue(entry.getBoolean("hardcore"))
        assertNoToken()
    }

    /** Boots [game], waits for its set, runs [body], and shuts it down. */
    private fun playing(
        game: File = exe,
        body: () -> Unit,
    ) {
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val surface = Surface(texture)
        NativeLibrary.surfaceChanged(surface, 640, 480, 1f)
        try {
            NativeLibrary.startGame(game.absolutePath, 1, false)
            assertTrue("padtest booted", started.await(30, TimeUnit.SECONDS))
            waitFor("the set") { NativeLibrary.getGameAchievements().status == GameAchievements.READY }
            body()
        } finally {
            NativeLibrary.shutdownGame()
            assertTrue("padtest stopped", stopped.await(30, TimeUnit.SECONDS))
            NativeLibrary.surfaceChanged(null, 0, 0, 1f)
            surface.release()
            texture.release()
        }
    }

    /** The records in the queue file, or none without one. */
    private fun queued(): List<JSONObject> {
        val file = File(folder, "queue.json")
        if (!file.exists()) return emptyList()
        val records = JSONObject(file.readText()).getJSONArray("records")
        return (0 until records.length()).map { records.getJSONObject(it) }
    }

    /** No file the store wrote holds the token. */
    private fun assertNoToken() {
        val files = folder.walkTopDown().filter { it.isFile }.toList()
        assertTrue("the store wrote files", files.isNotEmpty())
        for (file in files) assertFalse(file.path, file.readText().contains(TOKEN))
    }

    private fun waitFor(
        what: String,
        seconds: Int = 10,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    private fun initialize(settings: String) {
        File(dataRoot, "settings.ini").writeText(settings)
        val resources = Resources.extract(context)
        assertTrue(
            NativeLibrary.initialize(
                dataRoot.absolutePath,
                resources.absolutePath,
                biosDir.absolutePath,
                INSTALL_ID,
                TOKEN,
                folder.absolutePath,
            ),
        )
        waitFor("the sign-in") {
            NativeLibrary.getAchievementsAccount().state == AchievementsAccount.SIGNED_IN
        }
    }

    // The test app can't read /sdcard; the shell can. [path] may be a glob matching one file. The command is split at
    // spaces with no shell, so it runs in sh with ${'$'}{IFS} for its space: the glob matches a name with spaces.
    // False if there's nothing there.
    private fun copyAsShell(
        path: String,
        destination: File,
    ): Boolean {
        val output =
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .executeShellCommand("sh -c cat${'$'}{IFS}$path")
        destination.parentFile!!.mkdirs()
        ParcelFileDescriptor.AutoCloseInputStream(output).use { input ->
            destination.outputStream().use { input.copyTo(it) }
        }
        return destination.length() > 0
    }

    private companion object {
        const val INSTALL_ID = "test-install"
        const val SDCARD_TESTS = "/sdcard/GooseTest"
        const val PAD_TEST_DISC = "Pad Test #1 (100%)"

        // This install's file, signed in as the fake server's user.
        val SIGNED_IN_SETTINGS =
            """
            [Main]
            SettingsVersion = 3

            [Cheevos]
            Enabled = true
            Username = $USER

            [Android]
            InstallID = $INSTALL_ID
            """.trimIndent()
    }
}
