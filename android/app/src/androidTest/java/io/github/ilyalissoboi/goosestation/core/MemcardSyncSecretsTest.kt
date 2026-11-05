// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.app.KeystoreCipher
import io.github.ilyalissoboi.goosestation.app.SyncSecrets
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import io.github.ilyalissoboi.goosestation.storage.SharedPreferencesStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Memory card sync's secrets, kept in the Keystore through the app's callbacks, never in the working copy. The storage
 * is a closed port on this device, so a start finds it unreachable, which isn't an error. Every value is made up.
 * Each test runs in its own process.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class MemcardSyncSecretsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "memcard-sync-secrets")
    private val dataRoot = File(root, "data")
    private val settingsFile = File(dataRoot, "settings.ini")
    private val secrets = SyncSecrets(KeystoreCipher(KEY_ALIAS), SharedPreferencesStore(context, PREFS))
    private val startedAt = System.currentTimeMillis()

    private val callbacks =
        object : NativeCallbacks {
            override fun onGameStarted(
                title: String,
                serial: String,
                startPaused: Boolean,
                pauseOnDisconnect: Boolean,
            ) {}

            override fun onGameStopped() {}

            override fun onError(message: String) {}

            override fun storeMemcardSyncSecret(
                name: String,
                value: String?,
            ) = secrets.store(name, value)

            override fun loadMemcardSyncSecret(name: String) = secrets.load(name)

            override fun hasMemcardSyncSecret(name: String) = secrets.has(name)
        }

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        context
            .getSharedPreferences(PREFS, 0)
            .edit()
            .clear()
            .commit()
        ContentBridge.init(context)
        NativeLibrary.setCallbacks(callbacks)
    }

    @After
    fun tearDown() {
        context
            .getSharedPreferences(PREFS, 0)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun storedSecretStarts() {
        initialize()
        turnOn()
        secrets.store(SyncSecrets.SECRET_ACCESS_KEY, SECRET)

        NativeLibrary.applyMemcardSync()

        waitFor("sync to run") { NativeLibrary.getMemcardSyncState().let { it.running && !it.applying } }
        val state = NativeLibrary.getMemcardSyncState()
        assertEquals("", state.startError)
        assertTrue(state.hasSecret)
        assertEquals(SyncStatusKind.Status, state.statusKind)
    }

    @Test
    fun missingSecretIsIncomplete() {
        initialize()
        turnOn()

        NativeLibrary.applyMemcardSync()

        waitFor("the apply to end") { !NativeLibrary.getMemcardSyncState().applying }
        val state = NativeLibrary.getMemcardSyncState()
        assertEquals(SyncStatusKind.Incomplete, state.statusKind)
        assertFalse(state.hasSecret)
        assertFalse(state.running)
    }

    @Test
    fun desktopFlagsDontCountAsStored() {
        initialize()
        NativeLibrary.setBoolSetting("MemoryCardSync", "SecretStored", true)
        NativeLibrary.setBoolSetting("MemoryCardSync", "PassphraseStored", true)

        val state = NativeLibrary.getMemcardSyncState()

        assertFalse(state.hasSecret)
        assertFalse(state.hasPassphrase)
        assertTrue(state.passphraseRecorded)
    }

    @Test
    fun secretsStayOutOfTheWorkingCopy() {
        initialize()
        turnOn()
        secrets.store(SyncSecrets.SECRET_ACCESS_KEY, SECRET)
        secrets.store(SyncSecrets.ENCRYPTION_PASSPHRASE, PASSPHRASE)

        NativeLibrary.applyMemcardSync()
        waitFor("the apply to end") { !NativeLibrary.getMemcardSyncState().applying }

        val files = dataRoot.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val text = file.readBytes().decodeToString()
            assertFalse("$file holds the secret", SECRET in text)
            assertFalse("$file holds the passphrase", PASSPHRASE in text)
        }
    }

    @Test
    fun theLogNamesNoSecret() {
        initialize()
        turnOn()
        secrets.store(SyncSecrets.SECRET_ACCESS_KEY, SECRET)
        secrets.store(SyncSecrets.ENCRYPTION_PASSPHRASE, PASSPHRASE)

        NativeLibrary.applyMemcardSync()
        waitFor("the apply to end") { !NativeLibrary.getMemcardSyncState().applying }

        // Not through a shell: one word per argument. logcat takes the time as seconds since the epoch.
        val log = shell("logcat -d -t ${startedAt / 1000}.000")
        assertTrue("the log was read", log.isNotEmpty())
        assertFalse(SECRET in log)
        assertFalse(PASSPHRASE in log)
    }

    @Test
    fun emptyNameUsesThisDevicesName() {
        initialize()

        assertEquals(DEVICE_NAME, NativeLibrary.getMemcardSyncState().defaultDeviceName)
    }

    private fun turnOn() {
        NativeLibrary.setBoolSetting("MemoryCardSync", "Enabled", true)
        NativeLibrary.setStringSetting("MemoryCardSync", "RemoteURI", UNREACHABLE)
        NativeLibrary.setStringSetting("MemoryCardSync", "AccessKeyID", ACCESS_KEY_ID)
    }

    private fun initialize() {
        val resources = Resources.extract(context)
        assertTrue(
            NativeLibrary.initialize(
                dataRoot.absolutePath,
                resources.absolutePath,
                File(root, "bios").absolutePath,
                "test-install",
                null,
                File(root, "offline").absolutePath,
                DEVICE_NAME,
            ),
        )
    }

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting: $what")
            Thread.sleep(50)
        }
    }

    private fun shell(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
    }

    private companion object {
        const val KEY_ALIAS = "memcard-sync-secrets-test"
        const val PREFS = "memcard_sync_test"
        const val DEVICE_NAME = "Goose Test Device"
        const val ACCESS_KEY_ID = "GOOSEFAKEACCESSKEY"
        const val SECRET = "not-a-real-secret"
        const val PASSPHRASE = "not-a-real-passphrase"
        const val UNREACHABLE = "s3://goose-saves/emusync/v1?endpoint=http://127.0.0.1:1&path_style=1&region=us-east-1"
    }
}
