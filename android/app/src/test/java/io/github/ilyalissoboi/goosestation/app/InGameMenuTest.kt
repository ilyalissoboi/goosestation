// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.ArgbImage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-game menu's state and actions, with a fake core, an in-memory last slot, and one log of calls. */
class InGameMenuTest {
    private val calls = mutableListOf<String>()
    private val api = FakeGameCore(calls)
    private val lastSlots = MemoryLastSlots()
    private val picture = ArgbImage(2, 1, intArrayOf(-1, -1))

    private fun TestScope.menu(booted: Boolean = true): InGameMenu {
        val menu =
            InGameMenu(
                backgroundScope,
                StandardTestDispatcher(testScheduler),
                api,
                lastSlots,
                onMessage = { calls += "message $it" },
                onResume = { calls += "resume" },
                onExit = { calls += "exit $it" },
            )
        if (booted) {
            menu.gameStarted(SERIAL)
            runCurrent()
        }
        calls.clear()
        return menu
    }

    private fun InGameMenu.slots() = state.slots!!

    private fun InGameMenu.entries() = slots().entries!!

    @Test
    fun saveFocusesTheLastSlotElseSlot1() =
        runTest {
            val menu = menu()
            menu.openSlots(SlotMode.Save)
            runCurrent()
            assertEquals((1..10).toList(), menu.entries().map { it.slot })
            assertEquals(1, menu.slots().focus)

            lastSlots.set(SERIAL, 4)
            menu.closeSlots()
            menu.openSlots(SlotMode.Save)
            runCurrent()
            assertEquals(4, menu.slots().focus)

            lastSlots.set(SERIAL, SaveSlots.RESUME)
            menu.closeSlots()
            menu.openSlots(SlotMode.Save)
            runCurrent()
            assertEquals(1, menu.slots().focus)
        }

    @Test
    fun loadListsTheResumeStateFirstAndFocusesTheNewest() =
        runTest {
            api.times[SaveSlots.RESUME] = 100
            api.times[3] = 300
            val menu = menu()

            menu.openSlots(SlotMode.Load)
            runCurrent()

            // Only the saved ones: an empty slot can't be loaded.
            assertEquals(listOf(SaveSlots.RESUME, 3), menu.entries().map { it.slot })
            assertEquals(3, menu.slots().focus)
            assertEquals(300L, menu.entries().first { it.slot == 3 }.savedAt)
        }

    @Test
    fun loadFocusesTheLastSlotWhenItIsSaved() =
        runTest {
            api.times[SaveSlots.RESUME] = 500
            api.times[3] = 300
            lastSlots.set(SERIAL, 3)
            val menu = menu()

            menu.openSlots(SlotMode.Load)
            runCurrent()

            assertEquals(3, menu.slots().focus)
        }

    @Test
    fun loadWithNoSavesHasNothingToFocus() =
        runTest {
            lastSlots.set(SERIAL, 2)
            val menu = menu()

            menu.openSlots(SlotMode.Load)
            runCurrent()

            assertEquals(emptyList<SlotEntry>(), menu.entries())
            assertNull(menu.slots().focus)
        }

    @Test
    fun picturesArriveAfterTheList() =
        runTest {
            api.times[1] = 100
            api.times[2] = 200
            api.screenshots[1] = picture
            api.screenshots[2] = picture
            val menu = menu()

            menu.openSlots(SlotMode.Load)
            runCurrent()

            // The times first, then each saved slot's picture, one at a time.
            assertSame(picture, menu.entries().first { it.slot == 1 }.picture)
            assertSame(picture, menu.entries().first { it.slot == 2 }.picture)
            assertEquals(
                listOf(
                    "getSaveStateTimes $SERIAL",
                    "getSaveStateScreenshot $SERIAL 1",
                    "getSaveStateScreenshot $SERIAL 2",
                ),
                calls,
            )
        }

    @Test
    fun aStateWithoutAPictureStillShowsAsSaved() =
        runTest {
            api.times[4] = 400
            val menu = menu()

            menu.openSlots(SlotMode.Load)
            runCurrent()

            val entry = menu.entries().first { it.slot == 4 }
            assertEquals(400L, entry.savedAt)
            assertNull(entry.picture)
        }

    @Test
    fun aClosedPanelDropsLateResults() =
        runTest {
            api.times[1] = 100
            api.screenshots[1] = picture
            val menu = menu()

            menu.openSlots(SlotMode.Load)
            menu.closeSlots()
            runCurrent()

            assertNull(menu.state.slots)
        }

    @Test
    fun aReopenedPanelDropsTheOldOnesResults() =
        runTest {
            api.times[1] = 100
            val menu = menu()

            menu.openSlots(SlotMode.Load)
            menu.closeSlots()
            menu.openSlots(SlotMode.Save)
            runCurrent()

            assertEquals(SlotMode.Save, menu.slots().mode)
            assertEquals((1..10).toList(), menu.entries().map { it.slot })
        }

    @Test
    fun anEmptySlotSavesAtOnce() =
        runTest {
            val menu = menu()
            menu.openSlots(SlotMode.Save)
            runCurrent()
            calls.clear()

            menu.pickSlot(2)
            runCurrent()

            assertEquals(listOf("saveState 2", "message Saved to slot 2", "resume"), calls)
            assertNull(menu.state.slots)
            assertEquals(2, lastSlots.get(SERIAL))
        }

    @Test
    fun aFilledSlotAsksFirst() =
        runTest {
            api.times[3] = 300
            val menu = menu()
            menu.openSlots(SlotMode.Save)
            runCurrent()
            calls.clear()

            menu.pickSlot(3)
            runCurrent()
            assertEquals(3, menu.slots().replacing)
            assertEquals(emptyList<String>(), calls)

            menu.cancelReplace()
            assertNull(menu.slots().replacing)

            menu.pickSlot(3)
            menu.confirmReplace()
            runCurrent()
            assertEquals(listOf("saveState 3", "message Saved to slot 3", "resume"), calls)
        }

    @Test
    fun aFailedSaveKeepsTheMenuOpenWithTheReason() =
        runTest {
            api.saveError = "No space left on device."
            lastSlots.set(SERIAL, 5)
            val menu = menu()
            menu.openSlots(SlotMode.Save)
            runCurrent()
            calls.clear()

            menu.pickSlot(2)
            runCurrent()

            assertEquals(listOf("saveState 2"), calls)
            assertEquals(
                "Couldn't save to slot 2: No space left on device.",
                menu.slots().error,
            )
            assertFalse(menu.slots().busy)
            assertEquals(5, lastSlots.get(SERIAL))
        }

    @Test
    fun aLoadResumesAtTheLoadedPoint() =
        runTest {
            api.times[1] = 100
            val menu = menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            calls.clear()

            menu.pickSlot(1)
            runCurrent()

            assertEquals(listOf("loadState 1", "message Loaded slot 1", "resume"), calls)
            assertEquals(1, lastSlots.get(SERIAL))
        }

    @Test
    fun aFailedLoadKeepsTheGameWhereItWas() =
        runTest {
            api.times[1] = 100
            api.loadError = "The save state is from another version."
            val menu = menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            calls.clear()

            menu.pickSlot(1)
            runCurrent()

            assertEquals(listOf("loadState 1"), calls)
            assertEquals("Couldn't load slot 1: The save state is from another version.", menu.slots().error)
            assertNull(lastSlots.get(SERIAL))
        }

    @Test
    fun loadingTheResumeStateSaysSo() =
        runTest {
            api.times[SaveSlots.RESUME] = 100
            val menu = menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            calls.clear()

            menu.pickSlot(SaveSlots.RESUME)
            runCurrent()

            assertEquals(listOf("loadState -1", "message Loaded your resume state", "resume"), calls)
        }

    @Test
    fun anEmptySlotInLoadDoesNothing() =
        runTest {
            val menu = menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            calls.clear()

            menu.pickSlot(4)
            runCurrent()

            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun picksWaitWhileBusy() =
        runTest {
            val menu = menu()
            menu.openSlots(SlotMode.Save)
            runCurrent()
            calls.clear()

            menu.pickSlot(2)
            assertTrue(menu.slots().busy)
            menu.pickSlot(3)
            runCurrent()

            assertEquals(1, calls.count { it.startsWith("saveState") })
        }

    @Test
    fun nothingRunsBeforeTheBoot() =
        runTest {
            val menu = menu(booted = false)

            menu.openSlots(SlotMode.Save)
            menu.toggleFastForward()
            menu.toggleAnalogMode()
            menu.screenshot()
            menu.askReset()
            runCurrent()

            assertNull(menu.state.slots)
            assertFalse(menu.state.confirmingReset)
            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun aGameWithoutASerialHasNoSlots() =
        runTest {
            val menu = menu(booted = false)
            menu.gameStarted("")
            runCurrent()

            menu.openSlots(SlotMode.Save)
            runCurrent()

            assertNull(menu.state.slots)
        }

    @Test
    fun fastForwardTogglesAndResumes() =
        runTest {
            val menu = menu()

            menu.toggleFastForward()
            runCurrent()
            assertTrue(menu.state.fastForward)
            assertEquals(listOf("setFastForward true", "resume"), calls)

            calls.clear()
            menu.toggleFastForward()
            runCurrent()
            assertFalse(menu.state.fastForward)
            assertEquals(listOf("setFastForward false", "resume"), calls)
        }

    @Test
    fun fastForwardFollowsTheCore() =
        runTest {
            val menu = menu()

            menu.fastForwardChanged(true)

            assertTrue(menu.state.fastForward)
        }

    @Test
    fun analogModeAndScreenshotCallTheCoreAndResume() =
        runTest {
            val menu = menu()

            menu.toggleAnalogMode()
            runCurrent()
            menu.screenshot()
            runCurrent()

            assertEquals(listOf("toggleAnalogMode", "resume", "saveScreenshot", "resume"), calls)
        }

    @Test
    fun resetAsksThenResets() =
        runTest {
            val menu = menu()

            menu.askReset()
            assertTrue(menu.state.confirmingReset)
            menu.cancelReset()
            assertFalse(menu.state.confirmingReset)
            assertEquals(emptyList<String>(), calls)

            menu.askReset()
            menu.confirmReset()
            runCurrent()
            assertFalse(menu.state.confirmingReset)
            assertEquals(listOf("resetGame", "resume"), calls)
        }

    @Test
    fun analogModeShowsOnlyForTheDualShock() =
        runTest {
            api.analog = -1
            val menu = menu()
            assertNull(menu.state.analogMode)

            api.analog = 1
            menu.opened()
            runCurrent()
            assertEquals(true, menu.state.analogMode)

            api.analog = 0
            menu.opened()
            runCurrent()
            assertEquals(false, menu.state.analogMode)
        }

    @Test
    fun openingClosesAPanelLeftOpen() =
        runTest {
            val menu = menu()
            menu.openSlots(SlotMode.Save)
            menu.askReset()
            runCurrent()

            menu.opened()

            assertNull(menu.state.slots)
            assertFalse(menu.state.confirmingReset)
        }

    @Test
    fun resumeIsTheApps() =
        runTest {
            val menu = menu()

            menu.resume()

            assertEquals(listOf("resume"), calls)
        }

    @Test
    fun exitSavesTheResumeStateFirst() =
        runTest {
            val menu = menu()

            menu.exit()
            runCurrent()

            assertEquals(listOf("savesResumeState", "getSaveStateTimes $SERIAL", "saveResumeState", "exit null"), calls)
        }

    @Test
    fun exitBeforeTheBootSavesNothing() =
        runTest {
            val menu = menu(booted = false)

            menu.exit()
            runCurrent()

            assertEquals(listOf("exit null"), calls)
        }

    @Test
    fun aGameWithoutASerialExitsWithoutSaving() =
        runTest {
            val menu = menu(booted = false)
            menu.gameStarted("")
            runCurrent()
            calls.clear()

            menu.exit()
            runCurrent()

            assertEquals(listOf("exit null"), calls)
        }

    @Test
    fun aFailedResumeSaveStillExitsWithTheReason() =
        runTest {
            api.resumeError = "No space left on device."
            val menu = menu()

            menu.exit()
            runCurrent()

            assertEquals(
                listOf(
                    "savesResumeState",
                    "getSaveStateTimes $SERIAL",
                    "saveResumeState",
                    "exit No space left on device.",
                ),
                calls,
            )
        }

    @Test
    fun aSecondExitWhileSavingIsIgnored() =
        runTest {
            val menu = menu()

            menu.exit()
            assertTrue(menu.state.exiting)
            menu.exit()
            runCurrent()

            assertEquals(listOf("savesResumeState", "getSaveStateTimes $SERIAL", "saveResumeState", "exit null"), calls)
        }

    @Test
    fun resumeWhileTheExitSaveRunsIsIgnored() =
        runTest {
            val menu = menu()

            menu.exit()
            menu.resume()
            runCurrent()

            assertEquals(listOf("savesResumeState", "getSaveStateTimes $SERIAL", "saveResumeState", "exit null"), calls)
        }

    @Test
    fun theGameStoppingClearsEverything() =
        runTest {
            val menu = menu()
            menu.fastForwardChanged(true)
            menu.openSlots(SlotMode.Save)
            runCurrent()

            menu.gameStopped()

            assertEquals(GameMenuState(), menu.state)
        }

    @Test
    fun theCardBeingWrittenIsReadAsTheMenuOpens() =
        runTest {
            api.cardBusy = true
            val menu = menu()

            menu.opened()
            runCurrent()

            assertTrue(menu.state.cardBusy)
        }

    @Test
    fun exitAsksWhileTheCardIsBeingWritten() =
        runTest {
            api.cardBusy = true
            val menu = menu()
            menu.opened()
            runCurrent()
            calls.clear()

            menu.exit()
            runCurrent()
            assertTrue(menu.state.confirmingExit)
            assertEquals(emptyList<String>(), calls)

            menu.confirmExit()
            runCurrent()
            assertFalse(menu.state.confirmingExit)
            assertEquals(listOf("savesResumeState", "getSaveStateTimes $SERIAL", "saveResumeState", "exit null"), calls)
        }

    @Test
    fun cancellingExitStays() =
        runTest {
            api.cardBusy = true
            val menu = menu()
            menu.opened()
            runCurrent()
            calls.clear()

            menu.exit()
            menu.cancelExit()
            runCurrent()

            assertFalse(menu.state.confirmingExit)
            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun aSaveRefusedForTheCardSaysToResumeFirst() =
        runTest {
            api.saveError = "Cannot save state while memory card is being saved."
            val menu = menu()
            menu.openSlots(SlotMode.Save)
            runCurrent()

            menu.pickSlot(2)
            runCurrent()

            assertEquals(
                "The game is still saving to its memory card. Resume it for a moment, then save again.",
                menu.slots().error,
            )
        }

    @Test
    fun exitAsksBeforeReplacingAResumeState() =
        runTest {
            api.times[SaveSlots.RESUME] = 500
            val menu = menu()

            menu.exit()
            runCurrent()
            assertEquals(500L, menu.state.replacingResume)
            assertTrue(calls.none { it == "saveResumeState" || it.startsWith("exit") })

            calls.clear()
            menu.replaceResume()
            runCurrent()
            assertNull(menu.state.replacingResume)
            assertEquals(listOf("saveResumeState", "exit null"), calls)
        }

    @Test
    fun keepingTheResumeStateExitsWithoutSaving() =
        runTest {
            api.times[SaveSlots.RESUME] = 500
            val menu = menu()
            menu.exit()
            runCurrent()
            calls.clear()

            menu.keepResume()
            runCurrent()

            assertEquals(listOf("exit null"), calls)
        }

    @Test
    fun cancellingTheReplaceStaysInTheGame() =
        runTest {
            api.times[SaveSlots.RESUME] = 500
            val menu = menu()
            menu.exit()
            runCurrent()
            calls.clear()

            menu.cancelReplaceResume()
            runCurrent()

            assertNull(menu.state.replacingResume)
            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun withSaveStateOnExitOffExitSavesNothing() =
        runTest {
            api.resumeOn = false
            api.times[SaveSlots.RESUME] = 500
            val menu = menu()

            menu.exit()
            runCurrent()

            assertEquals(listOf("savesResumeState", "exit null"), calls)
        }

    private class MemoryLastSlots : LastSlotStore {
        private val slots = HashMap<String, Int>()

        override fun get(serial: String): Int? = slots[serial]

        override fun set(
            serial: String,
            slot: Int,
        ) {
            slots[serial] = slot
        }
    }

    private companion object {
        const val SERIAL = "SCUS-94900"
    }
}
