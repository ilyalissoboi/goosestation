// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What memory card sync says it's doing, against a fake S3 on the device. Each test runs in its own process. */
@RunWith(AndroidJUnit4::class)
class MemcardSyncStateTest {
    private val core = SyncTestCore(InstrumentationRegistry.getInstrumentation().targetContext, "memcard-sync-state")
    private val fake = FakeS3()

    @Before
    fun setUp() = core.start()

    @After
    fun tearDown() {
        fake.close()
        core.finish()
    }

    @Test
    fun startSyncsThenSynced() {
        core.configure(fake.uri())
        val before = core.changes.get()

        NativeLibrary.applyMemcardSync()

        core.waitFor("Synced") { core.state().activity == SyncActivity.Synced }
        assertTrue(fake.puts() > 0)
        assertTrue(core.changes.get() > before)
    }

    @Test
    fun unreachableIsOffline() {
        fake.unreachable = true
        core.configure(fake.uri())

        NativeLibrary.applyMemcardSync()

        core.waitFor("Offline", seconds = 40) { core.state().activity == SyncActivity.Offline }
        assertEquals("", core.state().startError)
    }

    @Test
    fun rejectedKeyNeedsAuth() {
        fake.rejectedKeys += "GOOSEREJECTEDKEY"
        core.configure(fake.uri(), accessKeyId = "GOOSEREJECTEDKEY")

        NativeLibrary.applyMemcardSync()

        core.waitFor("AuthRequired") { core.state().activity == SyncActivity.AuthRequired }
        assertEquals("The storage rejected the access key ID or secret.", core.state().startError)
    }

    @Test
    fun syncNowGoesSyncingThenSynced() {
        core.configure(fake.uri())
        NativeLibrary.applyMemcardSync()
        core.waitFor("Synced") { core.state().activity == SyncActivity.Synced }
        val listings = fake.listings()

        NativeLibrary.memcardSyncNow()

        assertEquals(SyncActivity.Syncing, core.state().activity)
        core.waitFor("Synced again") { core.state().activity == SyncActivity.Synced && fake.listings() > listings }
    }

    @Test
    fun stopIsNone() {
        core.configure(fake.uri())
        NativeLibrary.applyMemcardSync()
        core.waitFor("Synced") { core.state().activity == SyncActivity.Synced }

        NativeLibrary.setBoolSetting("MemoryCardSync", "Enabled", false)
        NativeLibrary.applyMemcardSync()

        core.waitFor("stopped") { !core.state().applying && core.state().activity == SyncActivity.None }
        assertFalse(core.state().running)
    }

    @Test
    fun periodicSyncRuns() {
        core.configure(fake.uri())
        NativeLibrary.applyMemcardSync()
        core.waitFor("Synced") { core.state().activity == SyncActivity.Synced }
        val listings = fake.listings()

        NativeLibrary.setMemcardSyncPeriodForTest(500)

        core.waitFor("two more syncs", seconds = 5) { fake.listings() >= listings + 2 }
    }

    // A pull brought a settings.ini with another storage: sync follows it.
    @Test
    fun aReloadedSettingsFileReapplies() {
        core.configure(fake.uri())
        NativeLibrary.applyMemcardSync()
        core.waitFor("Synced") { core.state().activity == SyncActivity.Synced }
        FakeS3().use { second ->
            val settings = java.io.File(core.dataRoot, "settings.ini")
            settings.writeText(settings.readText().replace(fake.uri(), second.uri()))

            NativeLibrary.reloadSettings()

            core.waitFor("the second storage") { second.listings() > 0 }
            assertEquals(second.uri(), core.state().remoteUri)
        }
    }
}
