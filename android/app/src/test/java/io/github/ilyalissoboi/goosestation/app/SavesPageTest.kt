// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.SaveStatePreview
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A game's Saves page, from its library tile, with a fake core. */
class SavesPageTest {
    private val calls = mutableListOf<String>()
    private val api = FakeGameCore(calls)
    private val crash = GameEntry("content://games/crash.cue", "Crash Bandicoot", SERIAL, "NTSC-U", 0, 0, null)
    private val spyro = GameEntry("content://games/spyro.cue", "Spyro the Dragon", "SCUS-94228", "NTSC-U", 0, 0, null)
    private val bios = GameEntry("content://games/bios", "BIOS", "", "", 0, 0, null)

    private fun TestScope.page(): SavesPage {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SavesPage(backgroundScope, dispatcher, SaveReader(api, dispatcher), api, { listOf(crash, spyro) }) {
            game,
            state,
            refused,
            ->
            calls += "play ${game.title} $state"
            if (api.hardcoreWouldStart) refused("Loading a state is off in hardcore mode")
        }
    }

    private fun TestScope.opened(game: GameEntry = crash): SavesPage {
        val page = page()
        page.open(game)
        runCurrent()
        calls.clear()
        return page
    }

    @Test
    fun opensOnThisGameWithItsSaves() =
        runTest {
            api.times[SaveSlots.RESUME] = 900
            api.times[2] = 200
            api.sharedTimes[4] = 400
            val page = opened()

            val state = page.state!!.lists
            assertEquals(SlotTab.ThisGame, state.tab)
            assertEquals(listOf(SaveSlots.RESUME, 2), state.entries!!.map { it.slot })
            assertEquals(listOf(4), state.shared!!.map { it.slot })

            page.switchTab()
            assertEquals(SlotTab.Shared, page.state!!.lists.tab)
        }

    @Test
    fun aGameWithoutAKeyOpensOnShared() =
        runTest {
            val page = opened(bios)

            assertEquals(SlotTab.Shared, page.state!!.lists.tab)
            assertEquals(emptyList<SlotEntry>(), page.state!!.lists.entries)
            assertTrue(calls.none { it.startsWith("getSaveStateTimes") })
        }

    @Test
    fun aStartsTheGameFromTheSave() =
        runTest {
            api.times[2] = 200
            val page = opened()

            page.pick(
                page.state!!
                    .lists.entries!!
                    .single(),
            )
            runCurrent()

            assertEquals(listOf("play Crash Bandicoot ${StateRef(SERIAL, 2)}"), calls)
        }

    @Test
    fun aSharedSlotStartsTheGameItHolds() =
        runTest {
            api.sharedTimes[4] = 400
            api.sharedHolds[4] = SaveStatePreview(null, 0, "SCUS-94228", "SPYRO", spyro.path, spyro.title)
            val page = opened()
            page.switchTab()

            page.pick(
                page.state!!
                    .lists.shared!!
                    .single(),
            )
            runCurrent()

            assertEquals(listOf("play Spyro the Dragon ${StateRef("", 4, shared = true)}"), calls)
        }

    @Test
    fun aSharedSlotOfAGameNotInTheLibraryIsNamed() =
        runTest {
            api.sharedTimes[4] = 400
            api.sharedHolds[4] = SaveStatePreview(null, 0, "SLUS-00067", "Tekken", "", "")
            val page = opened()
            page.switchTab()

            page.pick(
                page.state!!
                    .lists.shared!!
                    .single(),
            )
            runCurrent()

            assertTrue(calls.none { it.startsWith("play") })
            assertEquals(
                "Shared slot 4 needs Tekken (SLUS-00067), which isn't in your library.",
                page.state!!.lists.error,
            )
        }

    @Test
    fun hardcoreRefusesAStartButDeletes() =
        runTest {
            api.hardcoreWouldStart = true
            api.times[2] = 200
            val page = opened()
            assertTrue(page.state!!.hardcore)

            page.pick(
                page.state!!
                    .lists.entries!!
                    .single(),
            )
            runCurrent()
            assertTrue(calls.none { it.startsWith("play") })
            assertEquals("Loading a state is off in hardcore mode", page.state!!.lists.error)

            page.askDelete(
                page.state!!
                    .lists.entries!!
                    .single(),
            )
            page.confirmDelete()
            runCurrent()
            assertEquals("deleteState $SERIAL 2", calls.first { it.startsWith("deleteState") })
            assertEquals(emptyList<SlotEntry>(), page.state!!.lists.entries)
        }

    @Test
    fun deleteAsksThenRemovesAndRereads() =
        runTest {
            api.times[2] = 200
            api.times[5] = 500
            val page = opened()

            page.askDelete(
                page.state!!
                    .lists.entries!!
                    .first { it.slot == 2 },
            )
            assertEquals(
                2,
                page.state!!
                    .lists.deleting
                    ?.slot,
            )
            assertTrue(calls.none { it.startsWith("deleteState") })
            page.cancelDelete()
            assertNull(page.state!!.lists.deleting)

            page.askDelete(
                page.state!!
                    .lists.entries!!
                    .first { it.slot == 2 },
            )
            page.confirmDelete()
            runCurrent()
            assertEquals(
                listOf(5),
                page.state!!
                    .lists.entries!!
                    .map { it.slot },
            )
            assertEquals(
                5,
                page.state!!
                    .lists.focusRow
                    ?.slot,
            )
        }

    @Test
    fun aFailedDeleteSaysWhy() =
        runTest {
            api.times[2] = 200
            api.deleteError = "Permission denied."
            val page = opened()

            page.askDelete(
                page.state!!
                    .lists.entries!!
                    .single(),
            )
            page.confirmDelete()
            runCurrent()

            assertEquals("Couldn't delete that save: Permission denied.", page.state!!.lists.error)
        }

    // The deleted row can't be picked, nor another delete start, until the lists are read again.
    @Test
    fun aDeleteIsBusyUntilTheListsAreReadAgain() =
        runTest {
            api.times[2] = 200
            api.times[5] = 500
            val page = opened()
            val five =
                page.state!!
                    .lists.entries!!
                    .first { it.slot == 5 }
            var busyDuringReread: Boolean? = null
            api.onTimes = { busyDuringReread = page.state!!.lists.removing }

            page.askDelete(
                page.state!!
                    .lists.entries!!
                    .first { it.slot == 2 },
            )
            page.confirmDelete()
            assertTrue(page.state!!.lists.removing)
            page.pick(five)
            page.askDelete(five)
            page.switchTab()
            assertEquals(emptyList<String>(), calls)
            assertNull(page.state!!.lists.deleting)
            assertEquals(SlotTab.ThisGame, page.state!!.lists.tab)

            runCurrent()
            assertEquals(true, busyDuringReread)
            assertFalse(page.state!!.lists.removing)
            assertEquals(
                listOf(5),
                page.state!!
                    .lists.entries!!
                    .map { it.slot },
            )
        }

    @Test
    fun aFailedDeleteIsNoLongerBusy() =
        runTest {
            api.times[2] = 200
            api.deleteError = "Permission denied."
            val page = opened()

            page.askDelete(
                page.state!!
                    .lists.entries!!
                    .single(),
            )
            page.confirmDelete()
            runCurrent()

            assertFalse(page.state!!.lists.removing)
            assertEquals("Couldn't delete that save: Permission denied.", page.state!!.lists.error)
        }

    @Test
    fun aDeleteIsAskedOnlyForASavedRowStillListed() =
        runTest {
            api.times[2] = 200
            val page = opened()

            page.askDelete(SlotEntry(7, 700))
            assertNull(page.state!!.lists.deleting)
            page.askDelete(SlotEntry(2, null))
            assertNull(page.state!!.lists.deleting)
            page.askDelete(
                page.state!!
                    .lists.entries!!
                    .single(),
            )
            assertEquals(
                2,
                page.state!!
                    .lists.deleting
                    ?.slot,
            )
        }

    // A re-read after a delete stops the pictures the last read was still reading, rather than read them twice.
    @Test
    fun aRereadStopsTheLastPictureReads() =
        runTest {
            for (slot in listOf(2, 5, 8)) {
                api.times[slot] = slot * 100L
                api.screenshots[slot] = ArgbImage(2, 1, intArrayOf(-1, -1))
            }
            val page = page()
            var deleted = false
            api.onPreview = { slot ->
                if (slot == 2 && !deleted) {
                    deleted = true
                    page.askDelete(
                        page.state!!
                            .lists.entries!!
                            .first { it.slot == 2 },
                    )
                    page.confirmDelete()
                }
            }
            page.open(crash)
            runCurrent()

            // Slot 5's picture was under way as the re-read began; slot 8's, after it, is read by the re-read alone.
            assertEquals(1, calls.count { it == "getStatePreview $SERIAL 8" })
        }

    private companion object {
        const val SERIAL = "SCUS-94900"
    }
}
