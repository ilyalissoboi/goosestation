// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.SyncActivity
import io.github.ilyalissoboi.goosestation.core.SyncConflict
import io.github.ilyalissoboi.goosestation.core.SyncStatusKind
import io.github.ilyalissoboi.goosestation.core.SyncVersion
import io.github.ilyalissoboi.goosestation.settings.MemoryCoreSettings
import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Memory cards page: settings saved, then applied; secrets to the Keystore, never to the ini; the status line. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemcardSyncPageTest {
    private val calls = mutableListOf<String>()
    private val api = FakeCoreApi(calls)
    private val settings = MemoryCoreSettings()
    private val sealed = mutableMapOf<String, String>()
    private val secrets =
        SyncSecrets(
            object : TokenCipher {
                override fun encrypt(plain: ByteArray) = plain.reversedArray()

                override fun decrypt(sealed: ByteArray) = Opening.Opened(sealed.reversedArray())
            },
            object : KeyValueStore {
                override fun getString(key: String) = sealed[key]

                override fun putString(
                    key: String,
                    value: String?,
                ): Boolean {
                    if (value == null) sealed.remove(key) else sealed[key] = value
                    return true
                }
            },
        )

    private fun TestScope.page(): MemcardSyncPage {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val status = MemcardSyncStatus(backgroundScope, dispatcher, api)
        return MemcardSyncPage(backgroundScope, dispatcher, dispatcher, api, settings, secrets, status).also {
            status.refresh()
            runCurrent()
            calls.clear()
        }
    }

    @Test
    fun enablingSavesThenApplies() =
        runTest {
            val page = page()

            page.setEnabled(true)
            runCurrent()

            assertEquals("true", settings.values["MemoryCardSync/Enabled"])
            assertEquals("applyMemcardSync", calls.first())
            assertEquals(1, calls.count { it == "applyMemcardSync" })
        }

    @Test
    fun unchangedTextDoesNothing() =
        runTest {
            settings.setString("MemoryCardSync", "RemoteURI", "s3://goose-saves/emusync/v1")
            val page = page()

            page.setText("RemoteURI", "s3://goose-saves/emusync/v1")
            runCurrent()

            assertFalse("applyMemcardSync" in calls)
        }

    @Test
    fun textIsTrimmed() =
        runTest {
            val page = page()

            page.setText("AccessKeyID", "  GOOSEFAKEACCESSKEY \n")
            runCurrent()

            assertEquals("GOOSEFAKEACCESSKEY", settings.values["MemoryCardSync/AccessKeyID"])
            assertTrue("applyMemcardSync" in calls)
        }

    @Test
    fun secretIsTrimmedPassphraseIsNot() =
        runTest {
            val page = page()

            page.storeSecret(SyncSecrets.SECRET_ACCESS_KEY, " not-a-real-secret ")
            page.storeSecret(SyncSecrets.ENCRYPTION_PASSPHRASE, " not a real passphrase ")
            runCurrent()

            assertEquals("not-a-real-secret", secrets.load(SyncSecrets.SECRET_ACCESS_KEY))
            assertEquals(" not a real passphrase ", secrets.load(SyncSecrets.ENCRYPTION_PASSPHRASE))
            assertEquals(2, calls.count { it == "applyMemcardSync" })
        }

    @Test
    fun blankSecretIsIgnored() =
        runTest {
            val page = page()

            page.storeSecret(SyncSecrets.SECRET_ACCESS_KEY, "   ")
            runCurrent()

            assertNull(secrets.load(SyncSecrets.SECRET_ACCESS_KEY))
            assertFalse("applyMemcardSync" in calls)
        }

    @Test
    fun secretNeverReachesTheIni() =
        runTest {
            val page = page()

            page.storeSecret(SyncSecrets.SECRET_ACCESS_KEY, "not-a-real-secret")
            page.storeSecret(SyncSecrets.ENCRYPTION_PASSPHRASE, "not-a-real-passphrase")
            runCurrent()

            assertFalse(settings.values.values.any { "not-a-real" in it })
            assertEquals("true", settings.values["MemoryCardSync/SecretStored"])
            assertEquals("true", settings.values["MemoryCardSync/PassphraseStored"])
        }

    @Test
    fun removePassphraseDeletesIt() =
        runTest {
            val page = page()
            page.storeSecret(SyncSecrets.ENCRYPTION_PASSPHRASE, "not-a-real-passphrase")
            runCurrent()
            calls.clear()

            page.removePassphrase()
            runCurrent()

            assertNull(secrets.load(SyncSecrets.ENCRYPTION_PASSPHRASE))
            assertEquals("false", settings.values["MemoryCardSync/PassphraseStored"])
            assertTrue("applyMemcardSync" in calls)
        }

    @Test
    fun syncNowAsksTheCore() =
        runTest {
            val page = page()

            page.syncNow()
            runCurrent()

            assertTrue("memcardSyncNow" in calls)
        }

    @Test
    fun aChangeReadsTheStateAgain() =
        runTest {
            val page = page()
            api.syncState = api.syncState.copy(enabled = true, applying = true, statusKind = SyncStatusKind.Applying)

            page.setEnabled(true)
            runCurrent()

            assertTrue(page.state!!.applying)
        }

    @Test
    fun statusLineForEachKind() {
        val state = FakeCoreApi(mutableListOf()).syncState

        fun line(
            kind: SyncStatusKind,
            status: String = "",
            error: String = "",
        ) = SyncStatusWords.line(state.copy(statusKind = kind, status = status, startError = error))

        assertEquals(
            "Memory card sync is unavailable: the emusync library is missing.",
            line(SyncStatusKind.Unavailable),
        )
        assertEquals("Connecting to the storage…", line(SyncStatusKind.Applying))
        assertEquals(
            "The storage address is not valid.",
            line(SyncStatusKind.StartError, error = "The storage address is not valid."),
        )
        assertEquals("Off.", line(SyncStatusKind.Off))
        assertEquals(
            "Not syncing: fill in the storage, access key ID and secret access key.",
            line(SyncStatusKind.Incomplete),
        )
        assertEquals("Synced at 14:05.", line(SyncStatusKind.Status, status = "Synced at 14:05."))
        assertEquals("Syncing…", line(SyncStatusKind.Status, status = "Syncing..."))
        assertEquals("Starting…", line(SyncStatusKind.Status, status = "Starting..."))
        assertEquals("Syncing. Nothing has changed yet.", line(SyncStatusKind.Idle))
        // An activity of its own doesn't change the line: the line is the core's.
        assertEquals("Off.", SyncStatusWords.line(state.copy(activity = SyncActivity.Synced)))
    }

    private fun conflict(id: Long) =
        SyncConflict(
            id,
            "BASLUS-00067DRACULA (shared_card_1)",
            SyncVersion("Goose Test Device", 1, true, 1, false),
            listOf(SyncVersion("Desktop PC", 2, true, 1, false), SyncVersion("Laptop", 3, true, 1, false)),
            2,
        )

    @Test
    fun laterResolvesNothing() =
        runTest {
            api.conflicts = listOf(conflict(7))
            val page = page()
            page.loadConflicts()
            runCurrent()
            calls.clear()

            page.resolve(7, 3)
            runCurrent()

            assertFalse(calls.any { it.startsWith("resolveMemcardSyncConflict") })
            assertEquals(listOf(7L), page.conflicts!!.map { it.id })
        }

    @Test
    fun resolveDropsTheRow() =
        runTest {
            api.conflicts = listOf(conflict(7), conflict(8))
            val page = page()
            page.loadConflicts()
            runCurrent()

            page.resolve(7, 0)
            page.resolve(8, 2)
            runCurrent()

            assertTrue("resolveMemcardSyncConflict 7 true 0" in calls)
            assertTrue("resolveMemcardSyncConflict 8 false 1" in calls)
            assertEquals(emptyList<SyncConflict>(), page.conflicts)
        }

    @Test
    fun keepAllResolvesEachAsLocal() =
        runTest {
            api.conflicts = listOf(conflict(7), conflict(8))
            val page = page()
            page.loadConflicts()
            runCurrent()

            page.keepAllLocal()
            runCurrent()

            assertTrue("resolveMemcardSyncConflict 7 true 0" in calls)
            assertTrue("resolveMemcardSyncConflict 8 true 0" in calls)
            assertEquals(emptyList<SyncConflict>(), page.conflicts)
        }
}
