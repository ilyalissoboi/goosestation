// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Memory cards synced through a fake S3, as a game's cards are: opened, written, closed. Two devices share the
 * storage: this one, whose card is in the working copy, and [OTHER], another spelling of the storage's address with a
 * card of the same name of its own. Each test runs in its own process.
 */
@RunWith(AndroidJUnit4::class)
class MemcardSyncCardsTest {
    private val core = SyncTestCore(InstrumentationRegistry.getInstrumentation().targetContext, "memcard-sync-cards")
    private val fake = FakeS3()
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
    fun aWrittenSaveUploads() {
        asThisDevice()
        val puts = fake.puts()

        writeSave(card, V0)

        core.waitFor("the upload", seconds = 10) { fake.puts() > puts }
    }

    @Test
    fun anotherDevicesSaveArrivesOnOpen() {
        asOtherDevice()
        writeSave(otherCard, V0)
        asThisDevice()

        NativeLibrary.memcardCardOpenedForTest(card)

        assertArrayEquals(V0, NativeLibrary.readMemoryCardSaveForTest(card, SAVE))
        assertTrue(
            core.messages.toString(),
            core.messages.contains("MemcardSync-$card" to "Updated 1 save(s) from other devices on $CARD"),
        )
        NativeLibrary.memcardCardClosedForTest(card)
    }

    @Test
    fun bothChangedIsAConflict() {
        makeConflict()

        val conflict = NativeLibrary.getMemcardSyncConflicts().single()
        assertTrue(conflict.name, SAVE in conflict.name && "shared_card_1" in conflict.name)
        assertEquals(SyncTestCore.DEVICE_NAME, conflict.local!!.device)
        assertEquals(listOf(OTHER), conflict.remote.map { it.device })
        assertEquals(1, conflict.local!!.blocks)
        val newestIsOther = conflict.remote[0].modifiedMs > conflict.local!!.modifiedMs
        assertEquals(if (newestIsOther) 1 else 0, conflict.defaultChoice)
        assertEquals(1, core.state().conflictCount)
    }

    @Test
    fun resolvingTakesTheChoice() {
        makeConflict()
        val conflict = NativeLibrary.getMemcardSyncConflicts().single()

        NativeLibrary.resolveMemcardSyncConflict(conflict.id, false, 0)

        core.waitFor("the conflict to go") { NativeLibrary.getMemcardSyncConflicts().isEmpty() }
        NativeLibrary.memcardSyncNow()
        core.waitFor("the other device's version") {
            NativeLibrary.readMemoryCardSaveForTest(card, SAVE)?.contentEquals(V1_OTHER) == true
        }
    }

    @Test
    fun aHeldCardIsNotWrittenUntilReleased() {
        asThisDevice()
        writeSave(card, V0)
        asOtherDevice()
        openAndClose(otherCard)
        writeSave(otherCard, V1_OTHER)
        fake.unreachable = true
        asThisDevice(expect = SyncActivity.Offline)
        val before = File(card).readBytes()

        val held =
            NativeLibrary.memcardSyncHoldCard("memcards/$CARD") {
                fake.unreachable = false
                NativeLibrary.memcardSyncNow()
                Thread.sleep(3000)
                assertArrayEquals(before, File(card).readBytes())
            }

        assertTrue(held)
        core.waitFor("the save, once released") {
            NativeLibrary.readMemoryCardSaveForTest(card, SAVE)?.contentEquals(V1_OTHER) == true
        }
    }

    // emusync's worker is busy with slow storage, so registering a card times out: the card waits for a later run.
    @Test
    fun aHoldThatTimesOutLeavesTheCardForLater() {
        asThisDevice()
        fake.delayMs = 15_000
        NativeLibrary.memcardSyncNow()
        Thread.sleep(500)
        var ran = false

        val held = NativeLibrary.memcardSyncHoldCard("memcards/shared_card_2.mcd") { ran = true }

        fake.delayMs = 0
        assertFalse(held)
        assertFalse(ran)
    }

    // A game opening the card while the mirror holds it waits, so its session begins once the copy is done.
    @Test
    fun aCardOpenedDuringAHoldWaitsForIt() {
        asThisDevice()
        writeSave(card, V0)
        var openedAt = 0L
        var releasedAt = 0L
        val opener = Thread { NativeLibrary.memcardCardOpenedForTest(card).also { openedAt = System.nanoTime() } }

        assertTrue(
            NativeLibrary.memcardSyncHoldCard("memcards/$CARD") {
                opener.start()
                Thread.sleep(1500)
                releasedAt = System.nanoTime()
            },
        )
        opener.join(10_000)

        assertTrue("the open didn't wait for the hold", openedAt > releasedAt)
        // In the game's session now: the mirror can't hold it.
        assertFalse(NativeLibrary.memcardSyncHoldCard("memcards/$CARD") {})
        NativeLibrary.memcardCardClosedForTest(card)
    }

    @Test
    fun aCardInAGameIsNotHeld() {
        asThisDevice()
        NativeLibrary.memcardCardOpenedForTest(card)
        var ran = false

        val held = NativeLibrary.memcardSyncHoldCard("memcards/$CARD") { ran = true }

        assertFalse(held)
        assertFalse(ran)
        NativeLibrary.memcardCardClosedForTest(card)
    }

    @Test
    fun anExceptionInTheHoldEndsIt() {
        asThisDevice()
        openAndClose(card)

        assertThrows(IllegalStateException::class.java) {
            NativeLibrary.memcardSyncHoldCard("memcards/$CARD") { throw IllegalStateException("from the copy") }
        }

        var ran = false
        assertTrue(NativeLibrary.memcardSyncHoldCard("memcards/$CARD") { ran = true })
        assertTrue(ran)
    }

    @Test
    fun anUnreachableOpenUploadsLater() {
        asThisDevice()
        fake.unreachable = true
        val start = System.nanoTime()

        NativeLibrary.memcardCardOpenedForTest(card)

        assertTrue((System.nanoTime() - start) / 1_000_000 < 4000)
        NativeLibrary.writeMemoryCardSaveForTest(card, SAVE, V0)
        NativeLibrary.memcardCardWrittenForTest(card)
        NativeLibrary.memcardCardClosedForTest(card)
        val puts = fake.puts()
        fake.unreachable = false
        NativeLibrary.memcardSyncNow()
        core.waitFor("the upload") { fake.puts() > puts }
    }

    // This device's and the other's versions of the save, changed from one base on both.
    private fun makeConflict() {
        asThisDevice()
        writeSave(card, V0)
        core.waitFor("the upload", seconds = 10) { fake.keys().size > 2 }
        asOtherDevice()
        openAndClose(otherCard)
        assertArrayEquals(V0, NativeLibrary.readMemoryCardSaveForTest(otherCard, SAVE))
        // This device changes the save with sync off, the other with sync on.
        NativeLibrary.setBoolSetting("MemoryCardSync", "Enabled", false)
        NativeLibrary.applyMemcardSync()
        core.waitFor("sync off") { !core.state().applying && !core.state().running }
        NativeLibrary.writeMemoryCardSaveForTest(card, SAVE, V1_HERE)
        asOtherDevice()
        writeSave(otherCard, V1_OTHER)
        asThisDevice()
        NativeLibrary.memcardSyncNow()
        core.waitFor("the conflict") { NativeLibrary.getMemcardSyncConflicts().isNotEmpty() }
    }

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

    private fun openAndClose(path: String) {
        NativeLibrary.memcardCardOpenedForTest(path)
        NativeLibrary.memcardCardClosedForTest(path)
    }

    private fun asThisDevice(expect: SyncActivity = SyncActivity.Synced) = become("", fake.uri(), expect)

    private fun asOtherDevice() = become(OTHER, fake.uri(otherDevice = true), SyncActivity.Synced)

    private fun become(
        name: String,
        uri: String,
        expect: SyncActivity,
    ) {
        core.configure(uri)
        NativeLibrary.setStringSetting("MemoryCardSync", "DeviceName", name)
        NativeLibrary.applyMemcardSync()
        core.waitFor("$name to sync", seconds = 40) {
            core.state().let { !it.applying && it.running && it.activity == expect }
        }
        assertNotNull(core.state())
    }

    private companion object {
        const val CARD = "shared_card_1.mcd"
        const val SAVE = "BASLUS-00067DRACULA"
        const val OTHER = "Other"
        val V0 = save(251)
        val V1_HERE = save(241)
        val V1_OTHER = save(239)

        // One block: a title frame ("SC", one icon frame, one block), then a pattern the versions differ by. The core
        // lists only saves whose title frame says how many icon frames they have.
        private fun save(pattern: Int) =
            ByteArray(8192) { (it % pattern).toByte() }.apply {
                this[0] = 'S'.code.toByte()
                this[1] = 'C'.code.toByte()
                this[2] = 0x11
                this[3] = 1
            }
    }
}
