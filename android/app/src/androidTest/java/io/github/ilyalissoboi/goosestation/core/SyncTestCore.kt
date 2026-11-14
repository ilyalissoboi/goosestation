// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.content.Context
import io.github.ilyalissoboi.goosestation.app.KeystoreCipher
import io.github.ilyalissoboi.goosestation.app.SyncSecrets
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import io.github.ilyalissoboi.goosestation.storage.SharedPreferencesStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The core for memory card sync's device tests, in [root]: started with this device's name [DEVICE_NAME], its sync
 * secrets in a Keystore key and preferences of the tests' own, and the callbacks recording what sync reports. Only
 * made-up credentials ([FakeS3.ACCESS_KEY_ID], [FakeS3.SECRET]).
 */
class SyncTestCore(
    private val context: Context,
    name: String,
) {
    val root = File(context.cacheDir, name)
    val dataRoot = File(root, "data")
    val secrets = SyncSecrets(KeystoreCipher(KEY_ALIAS), SharedPreferencesStore(context, PREFS))

    /** How many times sync said its state changed. */
    val changes = AtomicInteger()

    /** The on-screen messages, as key and text. */
    val messages = CopyOnWriteArrayList<Pair<String, String>>()

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

            override fun onOsdMessage(
                key: String,
                text: String,
                seconds: Float,
                icon: ArgbImage?,
            ) {
                messages += key to text
            }

            override fun storeMemcardSyncSecret(
                name: String,
                value: String?,
            ) = secrets.store(name, value)

            override fun loadMemcardSyncSecret(name: String) = secrets.load(name)

            override fun hasMemcardSyncSecret(name: String) = secrets.has(name)

            override fun onMemcardSyncChanged() {
                changes.incrementAndGet()
            }
        }

    /** Starts the core with a fresh data root, and no secrets. */
    fun start() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        context
            .getSharedPreferences(PREFS, 0)
            .edit()
            .clear()
            .commit()
        ContentBridge.init(context)
        NativeLibrary.setCallbacks(callbacks)
        initializeCore(context, dataRoot, File(root, "bios"), File(root, "offline"), deviceName = DEVICE_NAME)
    }

    /** Sync on, with [uri], the made-up key ID and (unless not [withSecret]) secret; not applied yet. */
    fun configure(
        uri: String,
        accessKeyId: String = FakeS3.ACCESS_KEY_ID,
        withSecret: Boolean = true,
    ) {
        NativeLibrary.setBoolSetting("MemoryCardSync", "Enabled", true)
        NativeLibrary.setStringSetting("MemoryCardSync", "RemoteURI", uri)
        NativeLibrary.setStringSetting("MemoryCardSync", "AccessKeyID", accessKeyId)
        if (withSecret) secrets.store(SyncSecrets.SECRET_ACCESS_KEY, FakeS3.SECRET)
    }

    fun state() = NativeLibrary.getMemcardSyncState()

    /** Waits up to [seconds] for [condition]. */
    fun waitFor(
        what: String,
        seconds: Long = 20,
        condition: () -> Boolean,
    ) = waitFor(what, seconds, { " (state ${state()})" }, condition)

    /** Clears the tests' preferences. */
    fun finish() {
        context
            .getSharedPreferences(PREFS, 0)
            .edit()
            .clear()
            .commit()
    }

    companion object {
        const val DEVICE_NAME = "Goose Test Device"
        private const val KEY_ALIAS = "memcard-sync-secrets-test"
        private const val PREFS = "memcard_sync_test"
    }
}
