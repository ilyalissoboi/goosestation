// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.GameDisc
import io.github.ilyalissoboi.goosestation.core.SaveStatePreview
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The save lists the Saves page and the slot panel show ([SaveListModel]), with a fake core. */
class SaveListModelTest {
    private val calls = mutableListOf<String>()
    private val api = FakeGameCore(calls)
    private val changes = mutableListOf<SaveListState?>()
    private val picture = ArgbImage(2, 1, intArrayOf(-1, -1))
    private val discs =
        listOf(
            GameDisc("content://games/ac3 (disc 1).chd", SERIAL, "AC3 (Disc 1)", 1),
            GameDisc("content://games/ac3 (disc 2).chd", OTHER, "AC3 (Disc 2)", 2),
        )

    private fun TestScope.model(): SaveListModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SaveListModel(backgroundScope, dispatcher, SaveReader(api, dispatcher), api) { changes += it }
    }

    /** A model opened on [key]'s lists in [mode], read. */
    private fun TestScope.opened(
        mode: SlotMode = SlotMode.Load,
        key: String = SERIAL,
        lastSlot: (() -> Int?)? = null,
    ): SaveListModel {
        val model = model()
        model.open(key, PATH, if (key.isBlank()) emptyList() else discs, mode, lastSlot)
        runCurrent()
        calls.clear()
        return model
    }

    private val SaveListModel.lists get() = state!!

    @Test
    fun loadListsTheSavesAndOtherDiscs() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(SaveSlots.RESUME to 900L, 2 to 200L)
            api.serialTimes[OTHER] = mapOf(4 to 60L)
            api.sharedTimes[5] = 500
            val model = opened()

            assertEquals(SlotTab.ThisGame, model.lists.tab)
            assertEquals(listOf(SaveSlots.RESUME, 2), model.lists.entries!!.map { it.slot })
            assertEquals(listOf(OTHER to 4), model.lists.others.map { it.owner to it.slot })
            assertEquals(listOf(5), model.lists.shared!!.map { it.slot })
            assertEquals(model.lists.entries, model.lists.shownEntries)
            assertEquals(model.lists.others, model.lists.shownOthers)
        }

    // Review Focus 4: Save lists every slot, no other discs', and never asks to delete.
    @Test
    fun saveListsEverySlotAndNoOthers() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(2 to 200L)
            api.serialTimes[OTHER] = mapOf(4 to 60L)
            val model = opened(SlotMode.Save)

            assertEquals((1..SaveSlots.COUNT).toList(), model.lists.entries!!.map { it.slot })
            assertEquals(emptyList<SlotEntry>(), model.lists.others)
            assertEquals(SaveSlots.COUNT, model.lists.shared!!.size)

            model.askDelete(model.lists.entries!!.first { it.slot == 2 })
            assertNull(model.lists.deleting)
        }

    // Review Focus 3: a PSF or the BIOS has no key, so no slots of its own; its shared slots still work.
    @Test
    fun aGameWithoutAKeyListsOnlyShared() =
        runTest {
            api.sharedTimes[4] = 400
            val model = model()
            model.open("", PATH, emptyList(), SlotMode.Load)
            runCurrent()

            assertEquals(SlotTab.Shared, model.lists.tab)
            assertFalse(model.lists.hasKey)
            assertEquals(emptyList<SlotEntry>(), model.lists.entries)
            assertTrue(calls.none { it.startsWith("getSaveStateTimes") })
            assertEquals(listOf(4), model.lists.shownEntries!!.map { it.slot })

            model.askDelete(model.lists.shared!!.single())
            model.confirmDelete()
            runCurrent()
            assertTrue("deleteState shared 4" in calls)
            assertEquals(emptyList<SlotEntry>(), model.lists.shared)
        }

    @Test
    fun picturesComeOpenTabFirst() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(2 to 200L)
            api.sharedTimes[4] = 400
            api.screenshots[2] = picture
            val model = model()
            model.open(SERIAL, PATH, discs, SlotMode.Load)
            runCurrent()
            val ownFirst = calls.filter { it.startsWith("getStatePreview") }
            assertEquals(listOf("getStatePreview $SERIAL 2", "getStatePreview shared 4 $PATH"), ownFirst)
            assertEquals(
                picture,
                model.lists.entries!!
                    .single()
                    .picture,
            )

            calls.clear()
            model.open(SERIAL, PATH, discs, SlotMode.Load)
            model.switchTab()
            runCurrent()
            val sharedFirst = calls.filter { it.startsWith("getStatePreview") }
            assertEquals(listOf("getStatePreview shared 4 $PATH", "getStatePreview $SERIAL 2"), sharedFirst)
        }

    @Test
    fun focusFollowsTheLastSlot() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(2 to 200L, 3 to 300L)
            assertEquals(3, opened(lastSlot = { 3 }).lists.focus)
            assertNull(opened().lists.focus)
            assertEquals(1, opened(SlotMode.Save, lastSlot = { null }).lists.focus)
        }

    @Test
    fun aCloseDropsEveryRead() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(2 to 200L)
            val model = model()
            model.open(SERIAL, PATH, discs, SlotMode.Load)
            model.close()
            val seen = changes.size

            runCurrent()

            assertNull(model.state)
            assertNull(changes.last())
            assertEquals(seen, changes.size)
        }

    // The deleted row is listed until the re-read's lists are in: nothing starts until then.
    @Test
    fun deleteIsBusyUntilTheReread() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(2 to 200L, 5 to 500L)
            val model = opened()
            var busyDuringReread: Boolean? = null
            api.onTimes = { busyDuringReread = model.lists.removing }

            model.askDelete(model.lists.entries!!.first { it.slot == 2 })
            model.confirmDelete()
            assertTrue(model.lists.removing)
            assertFalse(model.lists.idle)
            model.askDelete(model.lists.entries!!.first { it.slot == 5 })
            model.switchTab()
            assertNull(model.lists.deleting)
            assertEquals(SlotTab.ThisGame, model.lists.tab)

            runCurrent()
            assertEquals(true, busyDuringReread)
            assertTrue(model.lists.idle)
            assertEquals(listOf(5), model.lists.entries!!.map { it.slot })
            assertEquals(5, model.lists.focusRow?.slot)
        }

    // Review Focus 2.
    @Test
    fun aFailedDeleteFocusesTheRowAgain() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(2 to 200L)
            api.deleteError = "Permission denied."
            val model = opened()
            val row = model.lists.entries!!.single()

            model.askDelete(row)
            model.confirmDelete()
            runCurrent()

            assertTrue(model.lists.idle)
            assertEquals("Couldn't delete that save: Permission denied.", model.lists.error)
            assertEquals(row, model.lists.focusRow)
            assertEquals(listOf(2), model.lists.entries!!.map { it.slot })
        }

    // Review Focus 1: the delete still runs, but its re-read lands on nothing; the next open starts idle.
    @Test
    fun aCloseMidDeleteLeavesTheNextOpenIdle() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(2 to 200L, 5 to 500L)
            val model = opened()
            model.askDelete(model.lists.entries!!.first { it.slot == 2 })
            model.confirmDelete()
            model.close()
            model.open(SERIAL, PATH, discs, SlotMode.Load)
            assertTrue(model.lists.idle)

            runCurrent()

            assertTrue("deleteState $SERIAL 2" in calls)
            assertTrue(model.lists.idle)
            assertNull(model.lists.focusRow)
            assertEquals(listOf(5), model.lists.entries!!.map { it.slot })
        }

    @Test
    fun aDeleteIsAskedOnlyForASavedRowStillListed() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(2 to 200L)
            val model = opened()

            model.askDelete(SlotEntry(7, 700))
            assertNull(model.lists.deleting)
            model.askDelete(SlotEntry(2, null))
            assertNull(model.lists.deleting)
            model.askDelete(model.lists.entries!!.single())
            assertEquals(2, model.lists.deleting?.slot)
        }

    @Test
    fun aRereadStopsTheLastPictureReads() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(2 to 200L, 5 to 500L, 8 to 800L)
            for (slot in listOf(2, 5, 8)) api.screenshots[slot] = picture
            val model = model()
            var deleted = false
            api.onPreview = { slot ->
                if (slot == 2 && !deleted) {
                    deleted = true
                    model.askDelete(model.lists.entries!!.first { it.slot == 2 })
                    model.confirmDelete()
                }
            }
            model.open(SERIAL, PATH, discs, SlotMode.Load)
            runCurrent()

            // Slot 5's picture was under way as the re-read began; slot 8's, after it, is read by the re-read alone.
            assertEquals(1, calls.count { it == "getStatePreview $SERIAL 8" })
        }

    @Test
    fun aTabSwitchClearsTheRowToFocus() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(2 to 200L, 5 to 500L)
            api.sharedTimes[4] = 400
            val model = opened()
            model.askDelete(model.lists.entries!!.first { it.slot == 2 })
            model.cancelDelete()
            assertEquals(2, model.lists.focusRow?.slot)

            model.switchTab()

            assertEquals(SlotTab.Shared, model.lists.tab)
            assertNull(model.lists.focusRow)
            assertEquals(model.lists.shared, model.lists.shownEntries)
            assertEquals(emptyList<SlotEntry>(), model.lists.shownOthers)
        }

    @Test
    fun noTabSwitchWhileDeleting() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(2 to 200L)
            val model = opened()

            model.askDelete(model.lists.entries!!.single())
            model.switchTab()

            assertEquals(SlotTab.ThisGame, model.lists.tab)
        }

    @Test
    fun readHoldsReadsTheGameOnlyWhenNotYetRead() =
        runTest {
            api.sharedTimes[4] = 400
            api.sharedHolds[4] = SaveStatePreview(null, 0, "SCUS-94228", "SPYRO", SPYRO, "Spyro the Dragon")
            val model = opened()
            val row = model.lists.shared!!.single()
            assertEquals(SPYRO, row.holds?.path)

            val again = async { model.readHolds(row) }
            runCurrent()
            assertEquals(row, again.await())
            assertTrue(calls.none { it.startsWith("getStatePreview") })

            val unread = async { model.readHolds(row.copy(holds = null)) }
            runCurrent()
            assertEquals(SPYRO, unread.await()?.holds?.path)
            assertEquals(1, calls.count { it == "getStatePreview shared 4 $PATH" })
        }

    // A state that says nothing of its game comes back without one: the owner says it can't be read.
    @Test
    fun readHoldsOfAnUnreadableStateHasNoGame() =
        runTest {
            api.sharedTimes[4] = 400
            val model = opened()

            val read = async { model.readHolds(model.lists.shared!!.single()) }
            runCurrent()

            assertNotNull(read.await())
            assertNull(read.await()!!.holds)
        }

    // Review Focus 5: a close or another open meanwhile drops the read.
    @Test
    fun readHoldsIsDroppedByACloseOrReread() =
        runTest {
            api.sharedTimes[4] = 400
            api.sharedHolds[4] = SaveStatePreview(null, 0, "SCUS-94228", "SPYRO", SPYRO, "Spyro the Dragon")
            val model = opened()
            val row =
                model.lists.shared!!
                    .single()
                    .copy(holds = null)

            // Under way (read on the game dispatcher) when the close comes.
            val closed = async(start = CoroutineStart.UNDISPATCHED) { model.readHolds(row) }
            model.close()
            runCurrent()
            assertNull(closed.await())

            model.open(SERIAL, PATH, discs, SlotMode.Load)
            runCurrent()
            val reopened = async(start = CoroutineStart.UNDISPATCHED) { model.readHolds(row) }
            model.open(SERIAL, PATH, discs, SlotMode.Load)
            runCurrent()
            assertNull(reopened.await())
        }

    @Test
    fun failShowsTheOwnersError() =
        runTest {
            api.serialTimes[SERIAL] = mapOf(2 to 200L)
            api.sharedTimes[4] = 400
            val model = opened()

            model.fail("Loading a state is off in hardcore mode")
            assertEquals("Loading a state is off in hardcore mode", model.lists.error)
            model.askDelete(model.lists.entries!!.single())
            assertNull(model.lists.error)
            model.cancelDelete()

            model.fail("Couldn't load slot 2: No.")
            model.switchTab()
            assertNull(model.lists.error)

            model.fail("Couldn't load slot 2: No.")
            model.clearError()
            assertNull(model.lists.error)
        }

    @Test
    fun focusOnNamesTheRowToFocus() =
        runTest {
            api.sharedTimes[4] = 400
            val model = opened()
            val row = model.lists.shared!!.single()

            model.focusOn(row)

            assertEquals(row, model.lists.focusRow)
        }

    private companion object {
        const val SERIAL = "SLPS-02020"
        const val OTHER = "SLPS-02021"
        const val PATH = "content://games/ac3 (disc 1).chd"
        const val SPYRO = "content://games/spyro.cue"
    }
}
