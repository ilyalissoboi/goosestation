// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.graphics.SurfaceTexture
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.core.FakeRetroAchievements.Companion.PASSWORD
import io.github.ilyalissoboi.goosestation.core.FakeRetroAchievements.Companion.TOKEN
import io.github.ilyalissoboi.goosestation.core.FakeRetroAchievements.Companion.USER
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/** Signing in to RetroAchievements, against [FakeRetroAchievements]: never the real server, never real credentials. */
@BootsGames
@RunWith(AndroidJUnit4::class)
class AchievementsSignInTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "achievements-sign-in")
    private val dataRoot = File(root, "data")
    private val biosDir = File(root, "bios")
    private val server = FakeRetroAchievements()

    // What the core reported, through NativeCallbacks.
    private val tokens = CopyOnWriteArrayList<String?>()
    private val expired = AtomicInteger()
    private val changes = AtomicInteger()
    private val started = CountDownLatch(1)
    private val stopped = CountDownLatch(1)

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
        NativeLibrary.setAchievementsServerForTest(server.url)
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

                override fun onAchievementsToken(token: String?) {
                    tokens += token
                }

                override fun onAchievementsLoginExpired() {
                    expired.incrementAndGet()
                }

                override fun onAchievementsChanged() {
                    changes.incrementAndGet()
                }
            },
        )
    }

    @After
    fun tearDown() {
        NativeLibrary.setCallbacks(null)
        NativeLibrary.setAchievementsServerForTest(null)
        server.close()
    }

    @Test
    fun signInWorks() {
        initialize()

        assertNull(NativeLibrary.signIn(USER, PASSWORD))

        val account = NativeLibrary.getAchievementsAccount()
        assertEquals(AchievementsAccount.SIGNED_IN, account.state)
        // rcheevos (v12) shows the server's User, its "case-corrected username", and ignores DisplayName.
        assertEquals(USER, account.displayName)
        assertEquals(300, account.score)
        assertEquals(940, account.softcoreScore)
        assertEquals(USER, NativeLibrary.getStringSetting("Cheevos", "Username", ""))
        assertTrue(NativeLibrary.getBoolSetting("Cheevos", "Enabled", false))
        assertEquals(listOf<String?>(TOKEN), tokens.toList())
        // The token stays in memory and the Keystore: no secrets.ini.
        assertFalse(File(dataRoot, "secrets.ini").exists())
        assertFalse(File(dataRoot, "settings.ini").readText().contains(TOKEN))
    }

    @Test
    fun aWrongPasswordSaysWhy() {
        initialize()

        assertEquals("Invalid username/password.", NativeLibrary.signIn(USER, "wrong"))

        assertEquals(AchievementsAccount.SIGNED_OUT, NativeLibrary.getAchievementsAccount().state)
        assertFalse(NativeLibrary.getBoolSetting("Cheevos", "Enabled", false))
        assertEquals(emptyList<String?>(), tokens.toList())
    }

    @Test
    fun anUnreachableServerSaysSo() {
        initialize()
        server.unreachable = true

        assertEquals("Couldn't reach RetroAchievements", NativeLibrary.signIn(USER, PASSWORD))
        assertEquals(AchievementsAccount.SIGNED_OUT, NativeLibrary.getAchievementsAccount().state)
    }

    @Test
    fun signOutClearsEverything() {
        initialize()
        assertNull(NativeLibrary.signIn(USER, PASSWORD))

        NativeLibrary.signOut()

        assertEquals(AchievementsAccount.SIGNED_OUT, NativeLibrary.getAchievementsAccount().state)
        assertEquals("", NativeLibrary.getStringSetting("Cheevos", "Username", ""))
        assertFalse(NativeLibrary.getBoolSetting("Cheevos", "Enabled", true))
        assertEquals(listOf(TOKEN, null), tokens.toList())
    }

    // Review Focus 1: an app started offline keeps its sign-in, for the next game start to retry.
    @Test
    fun anUnreachableServerKeepsTheToken() {
        server.unreachable = true
        initialize(SIGNED_IN_SETTINGS, TOKEN)

        val account = settledAccount()
        assertEquals(AchievementsAccount.NOT_CONNECTED, account.state)
        assertEquals(USER, account.username)
        assertEquals(emptyList<String?>(), tokens.toList())
        assertEquals(0, expired.get())
    }

    // The Settings page reads the account again on a change: an offline sign-in at startup must say it's done.
    @Test
    fun anOfflineSignInAtStartupReportsTheChange() {
        server.unreachable = true
        initialize(SIGNED_IN_SETTINGS, TOKEN)

        settledAccount()
        waitFor("a change report") { changes.get() > 0 }
    }

    @Test
    fun aRejectedTokenExpires() {
        server.rejectTokens = true
        initialize(SIGNED_IN_SETTINGS, TOKEN)

        waitFor("the expiry") { expired.get() == 1 }
        assertEquals(AchievementsAccount.SIGNED_OUT, settledAccount().state)
        assertEquals(listOf<String?>(null), tokens.toList())
    }

    // Needs the emulator's /sdcard/GooseTest bios/ps-30j.bin and games/padtest.psexe (docs/android.md).
    @Test
    fun aGameStartRetriesTheSignIn() {
        val exe = File(root, "padtest.psexe")
        assumeTrue(
            "No BIOS or padtest in /sdcard/GooseTest",
            copyFromShell("$SDCARD_TESTS/bios/ps-30j.bin", File(biosDir, "ps-30j.bin")) &&
                copyFromShell("$SDCARD_TESTS/games/padtest.psexe", exe),
        )
        server.unreachable = true
        initialize(SIGNED_IN_SETTINGS, TOKEN)
        assertEquals(AchievementsAccount.NOT_CONNECTED, settledAccount().state)
        server.unreachable = false

        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val surface = Surface(texture)
        NativeLibrary.surfaceChanged(surface, 640, 480, 1f, 0f)
        try {
            TestRenderer.apply()
            NativeLibrary.startGame(exe.absolutePath, 1, false)
            assertTrue("padtest booted", started.await(30, TimeUnit.SECONDS))
            waitFor("the sign-in") { NativeLibrary.getAchievementsAccount().state == AchievementsAccount.SIGNED_IN }
        } finally {
            NativeLibrary.shutdownGame()
            assertTrue("padtest stopped", stopped.await(30, TimeUnit.SECONDS))
            NativeLibrary.surfaceChanged(null, 0, 0, 1f, 0f)
            surface.release()
            texture.release()
        }
    }

    // Review Focus 3: neither the password nor the token is ever logged.
    @Test
    fun signInLogsNoSecrets() {
        shell("logcat -c")
        initialize()
        assertNull(NativeLibrary.signIn(USER, PASSWORD))
        NativeLibrary.signOut()

        val log = shell("logcat -d")
        assertTrue("the sign-in was logged", log.contains("dorequest.php"))
        assertFalse(log.contains(PASSWORD))
        assertFalse(log.contains(TOKEN))
    }

    /** The account once its sign-in has stopped connecting. */
    private fun settledAccount(): AchievementsAccount {
        waitFor("the sign-in to settle") {
            NativeLibrary.getAchievementsAccount().state != AchievementsAccount.CONNECTING
        }
        return NativeLibrary.getAchievementsAccount()
    }

    private fun initialize(
        settings: String? = null,
        token: String? = null,
    ) {
        if (settings != null) File(dataRoot, "settings.ini").writeText(settings)
        initializeCore(context, dataRoot, biosDir, File(dataRoot.parentFile, "offline"), token)
    }

    private fun shell(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
    }

    private companion object {
        const val INSTALL_ID = "test-install"
        const val SDCARD_TESTS = "/sdcard/GooseTest"

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
