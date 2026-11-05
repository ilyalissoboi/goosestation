// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.app.NativeMemcardEditorApi
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The memory card editor's Save and memory card sync: a card sync knows is written under sync's hold and syncs; any
 * other card is written on this device only, and sync still doesn't know it. Two devices share an in-process fake S3,
 * as in [MemcardSyncCardsTest].
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class MemcardEditorSyncTest {
    private val core = SyncTestCore(InstrumentationRegistry.getInstrumentation().targetContext, "memcard-editor-sync")
    private val fake = FakeS3()
    private val api = NativeMemcardEditorApi
    private val card get() = File(core.dataRoot, "memcards/$CARD").absolutePath
    private val otherCard get() = File(core.root, "other/$CARD").absolutePath

    @Before
    fun setUp() {
        core.start()
        File(otherCard).parentFile!!.mkdirs()
    }

    @After
    fun tearDown() {
        fake.close()
        core.finish()
    }

    @Test
    fun aCardNoGameUsedIsUnknownAndStaysLocal() {
        asThisDevice()
        assertFalse(api.isKnownToSync(card))

        val written = api.importSave(api.format(), "$NEW_SAVE.bin", save(233)).bytes!!
        assertEquals(MemcardWriteOutcome.Saved, api.writeCard(card, written, expected = null, force = false).outcome)
        NativeLibrary.memcardSyncNow()
        core.waitFor("Synced") { core.state().activity == SyncActivity.Synced }

        assertFalse(api.isKnownToSync(card))
        asOtherDevice()
        assertFalse(api.isKnownToSync(otherCard))
    }

    @Test
    fun aCardAGameUsedIsKnown() {
        asThisDevice()

        writeSave(card, V0)

        assertTrue(api.isKnownToSync(card))
    }

    @Test
    fun savingAKnownCardSyncs() {
        asThisDevice()
        writeSave(card, V0)
        val before = api.readCard(card)!!
        val edited = api.importSave(before, "$NEW_SAVE.bin", save(233)).bytes!!
        val puts = fake.puts()

        assertEquals(MemcardWriteOutcome.Saved, api.writeCard(card, edited, expected = before, force = false).outcome)

        core.waitFor("the upload", seconds = 10) { fake.puts() > puts }
        asOtherDevice()
        NativeLibrary.memcardCardOpenedForTest(otherCard)
        NativeLibrary.memcardCardClosedForTest(otherCard)
        assertArrayEquals(save(233), NativeLibrary.readMemoryCardSaveForTest(otherCard, NEW_SAVE))
    }

    @Test
    fun writeCardIsBusyWhileAGameHasTheCard() {
        asThisDevice()
        writeSave(card, V0)
        val before = File(card).readBytes()
        NativeLibrary.memcardCardOpenedForTest(card)

        val result = api.writeCard(card, api.format(), expected = before, force = false)

        NativeLibrary.memcardCardClosedForTest(card)
        assertEquals(MemcardWriteOutcome.Busy, result.outcome)
        assertArrayEquals(before, File(card).readBytes())
    }

    // As a game writes it: open, written, closed, then synced.
    private fun writeSave(
        path: String,
        data: ByteArray,
    ) {
        NativeLibrary.memcardCardOpenedForTest(path)
        assertTrue(NativeLibrary.writeMemoryCardSaveForTest(path, SAVE, data))
        NativeLibrary.memcardCardWrittenForTest(path)
        val puts = fake.puts()
        NativeLibrary.memcardCardClosedForTest(path)
        core.waitFor("the save's upload") { fake.puts() > puts }
        NativeLibrary.memcardSyncNow()
        core.waitFor("Synced") { core.state().activity == SyncActivity.Synced }
    }

    private fun asThisDevice() = become("", fake.uri())

    private fun asOtherDevice() = become(OTHER, fake.uri(otherDevice = true))

    private fun become(
        name: String,
        uri: String,
    ) {
        core.configure(uri)
        NativeLibrary.setStringSetting("MemoryCardSync", "DeviceName", name)
        NativeLibrary.applyMemcardSync()
        core.waitFor("$name to sync", seconds = 40) {
            core.state().let { !it.applying && it.running && it.activity == SyncActivity.Synced }
        }
        assertNotNull(core.state())
    }

    private companion object {
        const val CARD = "Pad Test_1.mcd"
        const val SAVE = "BASLUS-00067DRACULA"
        const val NEW_SAVE = "BASLUS-00099"
        const val OTHER = "Other"
        val V0 = save(251)

        // One block: a title frame ("SC", one icon frame), then a pattern.
        fun save(pattern: Int) =
            ByteArray(8192) { (it % pattern).toByte() }.apply {
                this[0] = 'S'.code.toByte()
                this[1] = 'C'.code.toByte()
                this[2] = 0x11
                this[3] = 1
            }
    }
}
