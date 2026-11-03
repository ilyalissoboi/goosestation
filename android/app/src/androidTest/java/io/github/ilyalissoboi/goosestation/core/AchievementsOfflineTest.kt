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
import io.github.ilyalissoboi.goosestation.core.FakeRetroAchievements.Companion.GAME_ID
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    // Fresh for each play (playing()).
    @Volatile private var started = CountDownLatch(1)

    @Volatile private var stopped = CountDownLatch(1)

    // What the core reported, through NativeCallbacks.
    private val sent = CopyOnWriteArrayList<Int>()
    private val refusals = CopyOnWriteArrayList<String>()
    private val expired = AtomicInteger()

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

                override fun onAchievementsSent(count: Int) {
                    sent += count
                }

                override fun onAchievementRefused(title: String) {
                    refusals += title
                }

                override fun onAchievementsLoginExpired() {
                    expired.incrementAndGet()
                }
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
        initialize(HARDCORE_SETTINGS)
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

    // Review Focus 1: what an earlier run couldn't send goes once its user is signed in, dated when it happened.
    @Test
    fun anEarlierRunsUnlockIsSentBackdated() {
        seed(unlockRecord())
        initialize(SIGNED_IN_SETTINGS)

        waitFor("the award") { server.awards().isNotEmpty() }
        val award = server.awards().single()
        assertEquals(ACHIEVEMENT_ID.toString(), award["a"])
        assertEquals(GAME_HASH, award["m"])
        assertEquals("0", award["h"])
        val offset = award["o"]!!.toLong()
        assertTrue("o = $offset", offset in 115..200)
        waitFor("the queue to empty") { queued().isEmpty() }
        waitFor("the sent report") { sent.isNotEmpty() }
        assertEquals(listOf(1), sent.toList())
    }

    @Test
    fun anEarlierRunsEntryIsSentBackdated() {
        seed(entryRecord())
        initialize(SIGNED_IN_SETTINGS)

        waitFor("the entry") { server.entries().isNotEmpty() }
        val entry = server.entries().single()
        assertEquals(LEADERBOARD_ID.toString(), entry["i"])
        assertEquals("4321", entry["s"])
        assertEquals(GAME_HASH, entry["m"])
        val offset = entry["o"]!!.toLong()
        assertTrue("o = $offset", offset in 115..200)
        waitFor("the queue to empty") { queued().isEmpty() }
    }

    // Review Focus 3: records are only ever sent as their own user.
    @Test
    fun anotherAccountsRecordsWait() {
        seed(unlockRecord(user = "swan"))
        initialize(SIGNED_IN_SETTINGS)

        Thread.sleep(5000)
        assertTrue(server.awards().isEmpty())
        assertEquals("swan", queued().single().getString("user"))
    }

    @Test
    fun aRefusedRecordIsDroppedAndReported() {
        server.refused += ACHIEVEMENT_ID
        seed(unlockRecord())
        initialize(SIGNED_IN_SETTINGS)

        waitFor("the refusal") { refusals.isNotEmpty() }
        assertEquals(listOf(ACHIEVEMENT_TITLE), refusals.toList())
        waitFor("the queue to empty") { queued().isEmpty() }
        assertTrue(sent.isEmpty())
    }

    // Review Focus 2: an unlock the server already has is delivered, never refused.
    @Test
    fun anAlreadyUnlockedRecordIsCleared() {
        server.alreadyUnlocked += ACHIEVEMENT_ID
        seed(unlockRecord())
        initialize(SIGNED_IN_SETTINGS)

        waitFor("the queue to empty") { server.awards().isNotEmpty() && queued().isEmpty() }
        waitFor("the sent report") { sent.isNotEmpty() }
        assertEquals(listOf(1), sent.toList())
        assertTrue(refusals.isEmpty())
    }

    @Test
    fun anExpiredTokenKeepsTheRecords() {
        server.rejectTokens = true
        seed(unlockRecord())
        initialize(SIGNED_IN_SETTINGS, signedIn = false)

        waitFor("the expiry") { expired.get() == 1 }
        assertTrue(server.awards().isEmpty())
        assertEquals(ACHIEVEMENT_ID, queued().single().getInt("id"))
    }

    @Test
    fun aSignOutKeepsTheRecords() {
        seed(unlockRecord(user = "swan"))
        initialize(SIGNED_IN_SETTINGS)

        NativeLibrary.signOut()

        assertEquals(AchievementsAccount.SIGNED_OUT, NativeLibrary.getAchievementsAccount().state)
        assertEquals("swan", queued().single().getString("user"))
    }

    // An app started offline, with the disc's set saved at an online start: casual, and its unlock waits.
    @Test
    fun aGameStartedOfflineRunsFromItsSavedSet() {
        seedSavedGame(DISC_HASH)
        server.unreachable = true
        initialize(HARDCORE_SETTINGS, signedIn = false)
        waitFor("the sign-in to fail") {
            NativeLibrary.getAchievementsAccount().state == AchievementsAccount.NOT_CONNECTED
        }

        playing(disc) {
            val game = NativeLibrary.getGameAchievements()
            assertTrue("offline", game.offline)
            assertFalse("casual", NativeLibrary.isHardcoreActive())
            waitFor("the unlock on disk", 30) { queued().any { it.getInt("id") == ACHIEVEMENT_ID } }
        }

        assertFalse(queued().single { it.getInt("id") == ACHIEVEMENT_ID }.getBoolean("hardcore"))
        // The sign-in built for the game goes with it.
        assertEquals(AchievementsAccount.NOT_CONNECTED, NativeLibrary.getAchievementsAccount().state)
        assertNoToken()
    }

    // Signed in, then the connection drops before a game starts: its set comes from the store too.
    @Test
    fun aSignedInGameWhoseSetCantLoadRunsFromItsSavedSet() {
        initialize(HARDCORE_SETTINGS)
        playing(disc) { assertTrue("hardcore online", NativeLibrary.isHardcoreActive()) }

        server.unreachable = true
        playing(disc) {
            assertTrue("offline", NativeLibrary.getGameAchievements().offline)
            assertFalse("casual", NativeLibrary.isHardcoreActive())
        }
    }

    // Review Focus 4: a reset can't bring hardcore back in a game started offline.
    @Test
    fun aResetKeepsAnOfflineGameCasual() {
        seedSavedGame(DISC_HASH)
        server.unreachable = true
        initialize(HARDCORE_SETTINGS, signedIn = false)

        playing(disc) {
            NativeLibrary.resetGame()
            Thread.sleep(2000)
            assertFalse("casual after the reset", NativeLibrary.isHardcoreActive())
            assertTrue("still offline", NativeLibrary.getGameAchievements().offline)
        }
    }

    @Test
    fun aGameNeverStartedOnlineHasNoAchievements() {
        server.unreachable = true
        initialize(SIGNED_IN_SETTINGS, signedIn = false)

        playing(ready = false) {
            waitFor("the failure") {
                NativeLibrary.getGameAchievements().status == GameAchievements.UNREACHABLE
            }
            assertFalse(NativeLibrary.getGameAchievements().offline)
        }
    }

    @Test
    fun anOfflineSessionCountsWhatWaits() {
        // Earned offline yesterday: unlocked today (rcheevos lists one under ten minutes old as recently unlocked).
        seedSavedGame(GAME_HASH)
        seed(unlockRecord(ageSeconds = 86_400))
        server.unreachable = true
        initialize(SIGNED_IN_SETTINGS, signedIn = false)

        playing {
            val unlocked =
                NativeLibrary
                    .getAchievementSections()
                    .filter { it.bucket == UNLOCKED_BUCKET }
                    .flatMap { it.achievements.toList() }
            assertTrue(unlocked.any { it.id == ACHIEVEMENT_ID })
        }
    }

    // Started offline, then the network comes back with no game running: the sign-in and the queue go.
    @Test
    fun aNetworkBackSendsTheQueue() {
        seed(unlockRecord())
        server.unreachable = true
        initialize(SIGNED_IN_SETTINGS, signedIn = false)
        waitFor("the sign-in to fail") {
            NativeLibrary.getAchievementsAccount().state == AchievementsAccount.NOT_CONNECTED
        }

        server.unreachable = false
        NativeLibrary.achievementsNetworkBack()

        waitFor("the award") { server.awards().isNotEmpty() }
        waitFor("the queue to empty") { queued().isEmpty() }
        assertEquals(AchievementsAccount.SIGNED_IN, NativeLibrary.getAchievementsAccount().state)
    }

    // Final review: a record whose send was turned away goes again at the next game start.
    @Test
    fun aKeptRecordIsSentAtTheNextGameStart() {
        server.busy = true
        seed(unlockRecord())
        initialize(SIGNED_IN_SETTINGS)
        waitFor("the turned-away award") { server.awards().isNotEmpty() }
        Thread.sleep(1000)
        assertEquals(1, queued().size)

        server.busy = false
        // No hash, so no set: the game can't earn it again, and only the start's send can clear it.
        NativeLibrary.setAchievementsGameHashForTest(null)
        playing(ready = false) {
            waitFor("the award at the game start") { server.awards().size >= 2 }
            waitFor("the queue to empty") { queued().isEmpty() }
        }
    }

    // PR review: a set that loaded online, with a session that couldn't, starts offline too: casual.
    @Test
    fun aSessionThatCantStartRunsCasual() {
        server.failSessions = true
        initialize(HARDCORE_SETTINGS)

        playing(disc) {
            assertTrue("offline", NativeLibrary.getGameAchievements().offline)
            assertFalse("casual", NativeLibrary.isHardcoreActive())
        }
    }

    // PR review: an unlock rcheevos gave up on (the server refused its token) goes at the next game start.
    @Test
    fun anUnlockRcheevosGaveUpOnIsSentAtTheNextGameStart() {
        initialize(SIGNED_IN_SETTINGS)
        playing {
            server.rejectAwards = true
            waitFor("the refused award", 30) { server.awards().isNotEmpty() }
            Thread.sleep(2000)
        }
        assertEquals(1, queued().size)

        server.rejectAwards = false
        NativeLibrary.setAchievementsGameHashForTest(null)
        playing(ready = false) {
            waitFor("the award at the game start") { server.awards().any { it.containsKey("o") } }
            waitFor("the queue to empty") { queued().isEmpty() }
        }
    }

    // PR review: turning achievements off and on drops rcheevos's retries, so what they held is sent again.
    @Test
    fun anUnlockIsSentAfterAchievementsAreTurnedOffAndOn() {
        initialize(SIGNED_IN_SETTINGS)
        playing {
            server.unreachable = true
            waitFor("the unlock on disk", 30) { queued().isNotEmpty() }
        }

        NativeLibrary.setBoolSetting("Cheevos", "Enabled", false)
        NativeLibrary.applySettings()
        server.unreachable = false
        NativeLibrary.setBoolSetting("Cheevos", "Enabled", true)
        NativeLibrary.applySettings()

        waitFor("the sign-in") { NativeLibrary.getAchievementsAccount().state == AchievementsAccount.SIGNED_IN }
        waitFor("the award with its age") { server.awards().any { it.containsKey("o") } }
        waitFor("the queue to empty") { queued().isEmpty() }
    }

    /** Writes what an online start of game [hash] would have saved: the account, the hash's game, and its set. */
    private fun seedSavedGame(hash: String) {
        File(folder, "users").mkdirs()
        File(folder, "sets").mkdirs()
        File(folder, "users/$USER.json").writeText(
            """{"version":1,"user":"$USER","display_name":"$USER","avatar_url":"","score":300,"softcore_score":940}""",
        )
        File(folder, "hashes.json").writeText("""{"version":1,"hashes":{"$hash":$GAME_ID}}""")
        File(folder, "sets/$GAME_ID.json").writeText(
            """{"version":1,"body":${JSONObject.quote(FakeRetroAchievements.GAME_SETS)}}""",
        )
    }

    /** Writes the queue an earlier run would have left. */
    private fun seed(vararg records: String) {
        folder.mkdirs()
        File(folder, "queue.json").writeText("""{"version":1,"records":[${records.joinToString(",")}]}""")
    }

    private fun unlockRecord(
        user: String = USER,
        ageSeconds: Long = 120,
    ) = """{"kind":"unlock","user":"$user","hash":"$GAME_HASH","id":$ACHIEVEMENT_ID,"hardcore":false,""" +
        """"score":0,"time":${now() - ageSeconds},"title":"$ACHIEVEMENT_TITLE"}"""

    private fun entryRecord(ageSeconds: Long = 120) =
        """{"kind":"entry","user":"$USER","hash":"$GAME_HASH","id":$LEADERBOARD_ID,"hardcore":true,""" +
            """"score":4321,"time":${now() - ageSeconds},"title":"Fastest lap"}"""

    private fun now() = System.currentTimeMillis() / 1000

    /** Boots [game], waits for its set (unless not [ready]), runs [body], and shuts it down. */
    private fun playing(
        game: File = exe,
        ready: Boolean = true,
        body: () -> Unit,
    ) {
        started = CountDownLatch(1)
        stopped = CountDownLatch(1)
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val surface = Surface(texture)
        NativeLibrary.surfaceChanged(surface, 640, 480, 1f)
        try {
            NativeLibrary.startGame(game.absolutePath, 1, false)
            assertTrue("padtest booted", started.await(30, TimeUnit.SECONDS))
            if (ready) waitFor("the set") { NativeLibrary.getGameAchievements().status == GameAchievements.READY }
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

    private fun initialize(
        settings: String,
        signedIn: Boolean = true,
    ) {
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
        if (signedIn) {
            waitFor("the sign-in") {
                NativeLibrary.getAchievementsAccount().state == AchievementsAccount.SIGNED_IN
            }
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

        /** The hash the core gives padtest's disc image (its PADTEST.EXE's). */
        const val DISC_HASH = "240326b8b35768674e822844d32da2bf"

        /** rcheevos's bucket of unlocked achievements (RC_CLIENT_ACHIEVEMENT_BUCKET_UNLOCKED). */
        const val UNLOCKED_BUCKET = 2

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

        // The same, with Hardcore on.
        val HARDCORE_SETTINGS = SIGNED_IN_SETTINGS.replace("Enabled = true", "Enabled = true\nChallengeMode = true")
    }
}
