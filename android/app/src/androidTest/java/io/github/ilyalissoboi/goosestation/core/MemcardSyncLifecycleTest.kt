// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Memory card sync while the app is in the background: it pushes what's waiting, then does nothing until the app is
 * back, which syncs at once. Periodic syncs every [PERIOD_MS] instead of 5 minutes. Each test runs in its own process.
 */
@RunWith(AndroidJUnit4::class)
class MemcardSyncLifecycleTest {
    private val core =
        SyncTestCore(InstrumentationRegistry.getInstrumentation().targetContext, "memcard-sync-lifecycle")
    private val fake = FakeS3()
    private val card get() = File(core.dataRoot, "memcards/shared_card_1.mcd").absolutePath

    @Before
    fun setUp() = core.start()

    @After
    fun tearDown() {
        fake.close()
        core.finish()
    }

    @Test
    fun backgroundStopsPeriodicSyncs() {
        startSyncing()

        NativeLibrary.setMemcardSyncBackgrounded(true)
        Thread.sleep(1000)
        val requests = fake.requests.size
        Thread.sleep(2000)

        assertEquals(requests, fake.requests.size)
    }

    @Test
    fun foregroundSyncsAtOnce() {
        startSyncing()
        NativeLibrary.setMemcardSyncBackgrounded(true)
        Thread.sleep(1000)
        val listings = fake.listings()

        NativeLibrary.setMemcardSyncBackgrounded(false)

        core.waitFor("a sync", seconds = 2) { fake.listings() > listings }
        core.waitFor("periodic syncs again", seconds = 3) { fake.listings() >= listings + 3 }
    }

    @Test
    fun backgroundUploadsWhatWasWritten() {
        startSyncing(periodic = false)
        NativeLibrary.memcardCardOpenedForTest(card)
        assertTrue(NativeLibrary.writeMemoryCardSaveForTest(card, "BASLUS-00067DRACULA", SAVE))
        val puts = fake.puts()

        NativeLibrary.setMemcardSyncBackgrounded(true)

        core.waitFor("the upload", seconds = 3) { fake.puts() > puts }
        NativeLibrary.memcardCardClosedForTest(card)
    }

    @Test
    fun aStartInTheBackgroundWaitsForTheForeground() {
        NativeLibrary.setMemcardSyncPeriodForTest(PERIOD_MS)
        NativeLibrary.setMemcardSyncBackgrounded(true)
        core.configure(fake.uri())

        NativeLibrary.applyMemcardSync()
        core.waitFor("running") { core.state().let { it.running && !it.applying } }
        val listings = fake.listings()
        // Anything that wakes sync's worker in the background still syncs nothing.
        NativeLibrary.setMemcardSyncPeriodForTest(PERIOD_MS)
        Thread.sleep(2000)
        assertEquals(listings, fake.listings())

        NativeLibrary.setMemcardSyncBackgrounded(false)
        core.waitFor("a sync", seconds = 3) { fake.listings() > listings }
    }

    @Test
    fun backAndForthQuicklyEndsResumed() {
        startSyncing()

        NativeLibrary.setMemcardSyncBackgrounded(true)
        NativeLibrary.setMemcardSyncBackgrounded(false)
        NativeLibrary.setMemcardSyncBackgrounded(true)
        NativeLibrary.setMemcardSyncBackgrounded(false)
        Thread.sleep(1000)
        val listings = fake.listings()

        core.waitFor("periodic syncs", seconds = 3) { fake.listings() >= listings + 2 }
    }

    private fun startSyncing(periodic: Boolean = true) {
        core.configure(fake.uri())
        NativeLibrary.applyMemcardSync()
        core.waitFor("Synced") { core.state().activity == SyncActivity.Synced }
        if (periodic) NativeLibrary.setMemcardSyncPeriodForTest(PERIOD_MS)
    }

    private companion object {
        const val PERIOD_MS = 500L
        val SAVE =
            ByteArray(8192) { (it % 251).toByte() }.apply {
                this[0] = 'S'.code.toByte()
                this[1] = 'C'.code.toByte()
                this[2] = 0x11
                this[3] = 1
            }
    }
}
