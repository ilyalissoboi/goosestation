// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.SaveStatePreview
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

            val state = page.state!!
            assertEquals(SlotTab.ThisGame, state.tab)
            assertEquals(listOf(SaveSlots.RESUME, 2), state.entries!!.map { it.slot })
            assertEquals(listOf(4), state.shared!!.map { it.slot })

            page.switchTab()
            assertEquals(SlotTab.Shared, page.state!!.tab)
        }

    @Test
    fun aGameWithoutAKeyOpensOnShared() =
        runTest {
            val page = opened(bios)

            assertEquals(SlotTab.Shared, page.state!!.tab)
            assertEquals(emptyList<SlotEntry>(), page.state!!.entries)
            assertTrue(calls.none { it.startsWith("getSaveStateTimes") })
        }

    @Test
    fun aStartsTheGameFromTheSave() =
        runTest {
            api.times[2] = 200
            val page = opened()

            page.pick(page.state!!.entries!!.single())
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

            page.pick(page.state!!.shared!!.single())
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

            page.pick(page.state!!.shared!!.single())
            runCurrent()

            assertTrue(calls.none { it.startsWith("play") })
            assertEquals("Shared slot 4 needs Tekken (SLUS-00067), which isn't in your library.", page.state!!.error)
        }

    @Test
    fun hardcoreRefusesAStartButDeletes() =
        runTest {
            api.hardcoreWouldStart = true
            api.times[2] = 200
            val page = opened()
            assertTrue(page.state!!.hardcore)

            page.pick(page.state!!.entries!!.single())
            runCurrent()
            assertTrue(calls.none { it.startsWith("play") })
            assertEquals("Loading a state is off in hardcore mode", page.state!!.error)

            page.askDelete(page.state!!.entries!!.single())
            page.confirmDelete()
            runCurrent()
            assertEquals("deleteState $SERIAL 2", calls.first { it.startsWith("deleteState") })
            assertEquals(emptyList<SlotEntry>(), page.state!!.entries)
        }

    @Test
    fun deleteAsksThenRemovesAndRereads() =
        runTest {
            api.times[2] = 200
            api.times[5] = 500
            val page = opened()

            page.askDelete(page.state!!.entries!!.first { it.slot == 2 })
            assertEquals(2, page.state!!.deleting?.slot)
            assertTrue(calls.none { it.startsWith("deleteState") })
            page.cancelDelete()
            assertNull(page.state!!.deleting)

            page.askDelete(page.state!!.entries!!.first { it.slot == 2 })
            page.confirmDelete()
            runCurrent()
            assertEquals(listOf(5), page.state!!.entries!!.map { it.slot })
            assertEquals(5, page.state!!.focusRow?.slot)
        }

    @Test
    fun aFailedDeleteSaysWhy() =
        runTest {
            api.times[2] = 200
            api.deleteError = "Permission denied."
            val page = opened()

            page.askDelete(page.state!!.entries!!.single())
            page.confirmDelete()
            runCurrent()

            assertEquals("Couldn't delete that save: Permission denied.", page.state!!.error)
        }

    private companion object {
        const val SERIAL = "SCUS-94900"
    }
}
