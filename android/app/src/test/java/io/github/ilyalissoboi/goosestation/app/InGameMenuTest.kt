// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.Achievement
import io.github.ilyalissoboi.goosestation.core.AchievementSection
import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.GameAchievements
import io.github.ilyalissoboi.goosestation.core.GameDisc
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.Leaderboard
import io.github.ilyalissoboi.goosestation.core.LeaderboardEntry
import io.github.ilyalissoboi.goosestation.core.LeaderboardPage
import io.github.ilyalissoboi.goosestation.core.PatchCode
import io.github.ilyalissoboi.goosestation.core.SaveStatePreview
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
    private val records = MemoryRecords()
    private val picture = ArgbImage(2, 1, intArrayOf(-1, -1))

    private fun TestScope.menu(booted: Boolean = true): InGameMenu {
        val menu =
            InGameMenu(
                backgroundScope,
                StandardTestDispatcher(testScheduler),
                api,
                lastSlots,
                records,
                onMessage = { calls += "message $it" },
                onResume = { calls += "resume" },
                onExit = { calls += "exit $it" },
                onSettings = { calls += "settings ${it?.serial}" },
                onSwitch = { calls += "switch ${it.gamePath} ${it.state.slot}" },
            )
        if (booted) {
            menu.gameStarted(game(SERIAL))
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

            // The times first (the shared slots' too), then each saved slot's picture, one at a time.
            assertSame(picture, menu.entries().first { it.slot == 1 }.picture)
            assertSame(picture, menu.entries().first { it.slot == 2 }.picture)
            assertEquals(
                listOf(
                    "getSaveStateTimes $SERIAL",
                    "getSharedStateTimes",
                    "getStatePreview $SERIAL 1",
                    "getStatePreview $SERIAL 2",
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

            assertEquals(listOf("saveState $SERIAL 2", "message Saved to slot 2", "resume"), calls)
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
            assertEquals(listOf("saveState $SERIAL 3", "message Saved to slot 3", "resume"), calls)
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

            assertEquals(listOf("saveState $SERIAL 2"), calls)
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

            assertEquals(listOf("loadState $SERIAL 1", "message Loaded slot 1", "resume"), calls)
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

            assertEquals(listOf("loadState $SERIAL 1"), calls)
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

            assertEquals(listOf("loadState $SERIAL -1", "message Loaded your resume state", "resume"), calls)
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
    fun aGameWithoutAKeyOpensOnShared() =
        runTest {
            api.sharedTimes[2] = 200
            val menu = menu(booted = false)
            menu.gameStarted(game(""))
            runCurrent()
            calls.clear()

            menu.openSlots(SlotMode.Save)
            runCurrent()

            val panel = menu.slots()
            assertEquals(SlotTab.Shared, panel.tab)
            assertFalse(panel.hasKey)
            assertEquals(emptyList<SlotEntry>(), panel.entries)
            assertEquals((1..10).toList(), panel.shared!!.map { it.slot })
            // Nothing is read under a blank key.
            assertTrue(calls.none { it.startsWith("getSaveStateTimes") })

            menu.pickShared(3)
            runCurrent()
            assertTrue("saveSharedState 3" in calls)
            assertTrue("message Saved to shared slot 3" in calls)
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

            assertEquals(
                listOf("savesResumeState", "getSaveStateTimes $SERIAL", "saveResumeState $SERIAL", "exit null"),
                calls,
            )
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
            menu.gameStarted(game(""))
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
                    "saveResumeState $SERIAL",
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

            assertEquals(
                listOf("savesResumeState", "getSaveStateTimes $SERIAL", "saveResumeState $SERIAL", "exit null"),
                calls,
            )
        }

    @Test
    fun resumeWhileTheExitSaveRunsIsIgnored() =
        runTest {
            val menu = menu()

            menu.exit()
            menu.resume()
            runCurrent()

            assertEquals(
                listOf("savesResumeState", "getSaveStateTimes $SERIAL", "saveResumeState $SERIAL", "exit null"),
                calls,
            )
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
            assertEquals(
                listOf("savesResumeState", "getSaveStateTimes $SERIAL", "saveResumeState $SERIAL", "exit null"),
                calls,
            )
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
            assertTrue(calls.none { it.startsWith("saveResumeState") || it.startsWith("exit") })

            calls.clear()
            menu.replaceResume()
            runCurrent()
            assertNull(menu.state.replacingResume)
            assertEquals(listOf("saveResumeState $SERIAL", "exit null"), calls)
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

    private fun game(key: String) = GameEntry("content://games/crash.cue", "Crash Bandicoot", key, "NTSC-U", 0, 0, null)

    @Test
    fun slotsUseTheGamesKeyNotTheDisc() =
        runTest {
            api.times[1] = 100
            val menu = menu(booted = false)
            menu.gameStarted(game("SLPS-02020"))
            runCurrent()
            calls.clear()

            menu.openSlots(SlotMode.Save)
            runCurrent()
            menu.pickSlot(2)
            runCurrent()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            menu.pickSlot(1)
            runCurrent()

            assertTrue(calls.toString(), "saveState SLPS-02020 2" in calls)
            assertTrue(calls.toString(), "loadState SLPS-02020 1" in calls)
            assertTrue(calls.toString(), calls.filter { it.startsWith("getSaveState") }.all { "SLPS-02020" in it })
        }

    private val ac3 =
        GameEntry(
            "content://games/ac3 (disc 1).chd",
            "Ace Combat 3",
            "SLPS-02020",
            "NTSC-J",
            0,
            0,
            null,
            listOf(
                GameDisc("content://games/ac3 (disc 1).chd", "SLPS-02020", "AC3 (Disc 1)", 1),
                GameDisc("content://games/ac3 (disc 2).chd", "SLPS-02021", "AC3 (Disc 2)", 2),
            ),
        )

    private fun TestScope.ac3Menu(): InGameMenu {
        val menu = menu(booted = false)
        menu.gameStarted(ac3)
        runCurrent()
        calls.clear()
        return menu
    }

    @Test
    fun loadListsOtherDiscsSavesAfterTheSlots() =
        runTest {
            api.serialTimes["SLPS-02020"] = mapOf(1 to 100L)
            api.serialTimes["SLPS-02021"] = mapOf(SaveSlots.RESUME to 50L, 4 to 60L)
            val menu = ac3Menu()

            menu.openSlots(SlotMode.Load)
            runCurrent()

            assertEquals(listOf(1), menu.entries().map { it.slot })
            val others = menu.slots().others
            assertEquals(listOf(SaveSlots.RESUME, 4), others.map { it.slot })
            assertEquals(listOf("SLPS-02021", "SLPS-02021"), others.map { it.owner })
            assertEquals(listOf(2, 2), others.map { it.disc })
            assertEquals(listOf(50L, 60L), others.map { it.savedAt })
        }

    @Test
    fun pickingAnOtherSaveLoadsIt() =
        runTest {
            api.serialTimes["SLPS-02021"] = mapOf(4 to 60L)
            val menu = ac3Menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            calls.clear()

            menu.pickOther("SLPS-02021", 4)
            runCurrent()

            assertEquals(
                listOf("loadState SLPS-02021 4", "getCurrentDisc", "message Loaded disc 2's slot 4", "resume"),
                calls,
            )
            assertNull(lastSlots.get("SLPS-02020"))
            assertNull(lastSlots.get("SLPS-02021"))
        }

    @Test
    fun aFailedOtherLoadSaysWhy() =
        runTest {
            api.serialTimes["SLPS-02021"] = mapOf(SaveSlots.RESUME to 60L)
            api.loadError = "Bad state."
            val menu = ac3Menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()

            menu.pickOther("SLPS-02021", SaveSlots.RESUME)
            runCurrent()

            assertEquals("Couldn't load that save: Bad state.", menu.slots().error)
        }

    @Test
    fun loadingAnOtherResumeStateSaysSo() =
        runTest {
            api.serialTimes["SLPS-02021"] = mapOf(SaveSlots.RESUME to 60L)
            val menu = ac3Menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            calls.clear()

            menu.pickOther("SLPS-02021", SaveSlots.RESUME)
            runCurrent()

            assertTrue(calls.toString(), "message Loaded disc 2's resume state" in calls)
        }

    @Test
    fun saveNeverListsOtherSaves() =
        runTest {
            api.serialTimes["SLPS-02021"] = mapOf(4 to 60L)
            val menu = ac3Menu()

            menu.openSlots(SlotMode.Save)
            runCurrent()

            assertTrue(menu.slots().others.isEmpty())
            assertTrue(calls.none { it == "getSaveStateTimes SLPS-02021" })
        }

    @Test
    fun aMultiDiscGamesRowsTakeTheirDisc() =
        runTest {
            api.times[1] = 100
            api.stateDiscs[1] = 2
            val menu = ac3Menu()

            menu.openSlots(SlotMode.Load)
            runCurrent()

            assertEquals(2, menu.entries().single().disc)
        }

    @Test
    fun aSingleDiscGamesRowsHaveNoDisc() =
        runTest {
            api.times[1] = 100
            api.stateDiscs[1] = 1
            val menu = menu()

            menu.openSlots(SlotMode.Load)
            runCurrent()

            assertNull(menu.entries().single().disc)
        }

    @Test
    fun changeDiscShowsOnlyForMultiDiscGames() =
        runTest {
            assertEquals(0, menu().state.discCount)
            assertEquals(2, ac3Menu().state.discCount)
        }

    @Test
    fun theCurrentDiscIsReadAsTheMenuOpens() =
        runTest {
            val menu = ac3Menu()
            api.discInDrive = 2

            menu.opened()
            runCurrent()

            assertEquals(2, menu.state.disc)
            assertEquals(2, records.lastDisc("SLPS-02020"))
        }

    @Test
    fun aSingleDiscGameDoesntAskForItsDisc() =
        runTest {
            val menu = menu()

            menu.opened()
            runCurrent()

            assertTrue(calls.none { it == "getCurrentDisc" })
        }

    @Test
    fun pickingAnotherDiscInsertsItAndResumes() =
        runTest {
            val menu = ac3Menu()
            menu.openDiscs()
            assertEquals(
                listOf(1, 2),
                menu.state.discs!!
                    .discs
                    .map { it.number },
            )
            assertEquals(1, menu.state.discs!!.current)

            menu.pickDisc(2)
            runCurrent()

            assertEquals(listOf("changeDisc 2", "message Disc 2 inserted", "resume"), calls)
            assertNull(menu.state.discs)
            assertEquals(2, menu.state.disc)
            assertEquals(2, records.lastDisc("SLPS-02020"))
        }

    @Test
    fun pickingTheCurrentDiscOnlyCloses() =
        runTest {
            val menu = ac3Menu()
            menu.openDiscs()

            menu.pickDisc(1)
            runCurrent()

            assertEquals(emptyList<String>(), calls)
            assertNull(menu.state.discs)
        }

    @Test
    fun aMissingDiscKeepsTheMenuOpen() =
        runTest {
            api.discError = "Disc 2 isn't in your library any more."
            val menu = ac3Menu()
            menu.openDiscs()

            menu.pickDisc(2)
            runCurrent()

            assertEquals(listOf("changeDisc 2"), calls)
            assertEquals("Disc 2 isn't in your library any more.", menu.state.discs!!.error)
            assertFalse(menu.state.discs!!.busy)
            assertEquals(1, menu.state.disc)
        }

    @Test
    fun aLoadRecordsItsDisc() =
        runTest {
            api.times[1] = 100
            val menu = ac3Menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            api.discInDrive = 2

            menu.pickSlot(1)
            runCurrent()

            assertEquals(2, menu.state.disc)
            assertEquals(2, records.lastDisc("SLPS-02020"))
        }

    @Test
    fun onePanelAtATime() =
        runTest {
            val menu = ac3Menu()
            menu.openSlots(SlotMode.Save)
            menu.openDiscs()
            assertNull(menu.state.slots)

            menu.openSlots(SlotMode.Load)
            assertNull(menu.state.discs)
        }

    @Test
    fun aSingleDiscGameHasNoDiscPanel() =
        runTest {
            val menu = menu()

            menu.openDiscs()

            assertNull(menu.state.discs)
        }

    @Test
    fun codesOpenBesideTheMenu() =
        runTest {
            api.codes = listOf(PatchCode(0, "Player", "Infinite HP:In battle", false, true))
            val menu = menu()
            menu.openSlots(SlotMode.Save)

            menu.openCodes()
            assertNull(menu.state.slots)
            runCurrent()

            val panel = menu.state.codes!!
            assertEquals("Crash Bandicoot.cht", panel.fileName)
            val row =
                panel.groups!!
                    .single()
                    .codes
                    .single()
            assertEquals("Infinite HP" to "In battle", row.name to row.note)
            assertTrue(row.on)
        }

    @Test
    fun switchingACodeKeepsTheMenuOpen() =
        runTest {
            api.codes = listOf(PatchCode(0, "", "A", false, false), PatchCode(1, "", "B", false, false))
            val menu = menu()
            menu.openCodes()
            runCurrent()
            calls.clear()

            menu.setCode(1, true)
            assertTrue(
                menu.state.codes!!
                    .groups!!
                    .single()
                    .codes[1]
                    .on,
            )
            runCurrent()

            assertEquals(listOf("setPatchCode 1 true"), calls)
            assertTrue(
                menu.state.codes!!
                    .groups!!
                    .single()
                    .codes[1]
                    .on,
            )
        }

    @Test
    fun aFailedSwitchPutsTheRowBack() =
        runTest {
            api.codes = listOf(PatchCode(0, "", "Infinite HP", false, false))
            api.codeError = "Disk full."
            val menu = menu()
            menu.openCodes()
            runCurrent()

            menu.setCode(0, true)
            runCurrent()

            val panel = menu.state.codes!!
            assertFalse(
                panel.groups!!
                    .single()
                    .codes
                    .single()
                    .on,
            )
            assertEquals("Couldn't switch Infinite HP: Disk full.", panel.error)
        }

    @Test
    fun applyingSaysSo() =
        runTest {
            api.codes = listOf(PatchCode(0, "", "Max Money Now:Once", true, false))
            val menu = menu()
            menu.openCodes()
            runCurrent()
            calls.clear()

            menu.applyCode(0)
            runCurrent()

            assertEquals(listOf("applyPatchCode 0"), calls)
            assertEquals("Applied: Max Money Now", menu.state.codes!!.applied)
            assertTrue(calls.none { it == "resume" })
        }

    @Test
    fun aGameWithNoCodesNamesTheFile() =
        runTest {
            val menu = menu()

            menu.openCodes()
            runCurrent()

            assertEquals(emptyList<PatchCodeGroup>(), menu.state.codes!!.groups)
            assertEquals("Crash Bandicoot.cht", menu.state.codes!!.fileName)
        }

    @Test
    fun closingTheCodesDropsALateRead() =
        runTest {
            val menu = menu()

            menu.openCodes()
            menu.closeCodes()
            runCurrent()

            assertNull(menu.state.codes)
        }

    @Test
    fun hardcoreBlocksLoadCodesAndFastForward() =
        runTest {
            api.hardcore = true
            api.times[SaveSlots.RESUME] = 100
            val menu = menu()
            assertTrue(menu.state.hardcore)

            menu.openSlots(SlotMode.Load)
            menu.openCodes()
            menu.toggleFastForward()
            runCurrent()

            assertNull(menu.state.slots)
            assertNull(menu.state.codes)
            assertFalse(menu.state.fastForward)
            assertFalse(
                calls.any {
                    it.startsWith("loadState") || it == "getPatchCodes" ||
                        it.startsWith("setFastForward")
                },
            )
        }

    @Test
    fun saveStillWorksInHardcore() =
        runTest {
            api.hardcore = true
            val menu = menu()

            menu.openSlots(SlotMode.Save)
            runCurrent()
            menu.pickSlot(1)
            runCurrent()

            assertTrue(calls.contains("saveState $SERIAL 1"))
        }

    // Review Focus 2: hardcore drops a few seconds after boot for a game RetroAchievements doesn't know.
    @Test
    fun loadComesBackWhenHardcoreDrops() =
        runTest {
            api.hardcore = true
            api.times[SaveSlots.RESUME] = 100
            val menu = menu()
            assertTrue(menu.state.hardcore)

            api.hardcore = false
            menu.achievementsChanged()
            runCurrent()
            assertFalse(menu.state.hardcore)
            menu.openSlots(SlotMode.Load)
            runCurrent()

            assertEquals(SaveSlots.RESUME, menu.entries().first().slot)
        }

    private fun achievementsReady(
        achievements: Boolean = true,
        leaderboards: Boolean = true,
    ) {
        api.achievementsOfGame =
            GameAchievements(GameAchievements.READY, 1, 2, 5, 10, achievements, leaderboards, false)
        api.sections =
            listOf(AchievementSection(2, "Unlocked", arrayOf(Achievement(7, "First", "", 5, "", 100, "", 0f))))
    }

    @Test
    fun achievementsOpenOnTheirTab() =
        runTest {
            achievementsReady()
            val menu = menu()

            menu.openAchievements()
            runCurrent()

            val panel = menu.state.cheevos!!
            assertEquals(CheevoTab.Achievements, panel.tab)
            assertEquals(
                listOf(7),
                panel.sections!!
                    .single()
                    .achievements
                    .map { it.id },
            )
            assertTrue(calls.contains("getAchievementSections"))
        }

    @Test
    fun onlyLeaderboardsOpensThatTab() =
        runTest {
            achievementsReady(achievements = false)
            val menu = menu()

            menu.openAchievements()
            runCurrent()

            assertEquals(CheevoTab.Leaderboards, menu.state.cheevos!!.tab)
        }

    @Test
    fun switchTabWithOneTabDoesNothing() =
        runTest {
            achievementsReady(leaderboards = false)
            val menu = menu()
            menu.openAchievements()
            runCurrent()

            menu.switchTab()

            assertEquals(CheevoTab.Achievements, menu.state.cheevos!!.tab)
        }

    @Test
    fun aRefreshWhileOpenReadsAgain() =
        runTest {
            achievementsReady()
            val menu = menu()
            menu.openAchievements()
            runCurrent()
            calls.clear()
            api.sections =
                listOf(AchievementSection(2, "Unlocked", arrayOf(Achievement(8, "Second", "", 5, "", 200, "", 0f))))

            menu.achievementsChanged()
            runCurrent()

            assertEquals(listOf("getAchievementSections"), calls.filter { it == "getAchievementSections" })
            assertEquals(
                listOf(8),
                menu.state.cheevos!!
                    .sections!!
                    .single()
                    .achievements
                    .map { it.id },
            )
        }

    @Test
    fun notReadyDoesNotOpen() =
        runTest {
            api.achievementsOfGame = GameAchievements(GameAchievements.LOADING, 0, 0, 0, 0, false, false, false)
            val menu = menu()

            menu.openAchievements()
            runCurrent()

            assertNull(menu.state.cheevos)
        }

    private val board = Leaderboard(31, "Sunny Flight", "Fastest time")
    private val sunnyPage =
        LeaderboardPage(1284, arrayOf(LeaderboardEntry(1, "speedgoose", "0:42.10")), emptyArray(), -1)

    private fun TestScope.leaderboardsOpen(): InGameMenu {
        achievementsReady()
        api.boards = listOf(board, Leaderboard(32, "Gem Rush", "Most gems"))
        val menu = menu()
        menu.openAchievements()
        runCurrent()
        menu.switchTab()
        runCurrent()
        calls.clear()
        return menu
    }

    @Test
    fun openingALeaderboardFetches() =
        runTest {
            val menu = leaderboardsOpen()
            assertEquals(
                2,
                menu.state.cheevos!!
                    .leaderboards!!
                    .size,
            )

            menu.openLeaderboard(31)

            val panel = menu.state.cheevos!!
            assertEquals(31, panel.open!!.id)
            assertNull(panel.page)
            runCurrent()
            assertEquals(listOf("fetchLeaderboard 31 ${panel.request}"), calls)
        }

    // Review Focus 5: a reply that comes after its board was closed, or another opened, is never shown.
    @Test
    fun aLateLeaderboardReplyIsDropped() =
        runTest {
            val menu = leaderboardsOpen()
            menu.openLeaderboard(31)
            val first = menu.state.cheevos!!.request
            menu.closeLeaderboard()
            menu.openLeaderboard(32)
            val second = menu.state.cheevos!!.request

            menu.leaderboardFetched(first, sunnyPage, null)
            assertNull(menu.state.cheevos!!.page)
            menu.leaderboardFetched(second, sunnyPage, null)

            assertEquals(
                32,
                menu.state.cheevos!!
                    .open!!
                    .id,
            )
            assertEquals(
                1284,
                menu.state.cheevos!!
                    .page!!
                    .total,
            )
        }

    @Test
    fun switchingTabsClosesTheLeaderboard() =
        runTest {
            val menu = leaderboardsOpen()
            menu.openLeaderboard(31)

            menu.switchTab()
            menu.switchTab()
            runCurrent()

            assertNull(menu.state.cheevos!!.open)
        }

    @Test
    fun anErrorShowsInThePanel() =
        runTest {
            val menu = leaderboardsOpen()
            menu.openLeaderboard(31)

            menu.leaderboardFetched(menu.state.cheevos!!.request, null, "Couldn't reach RetroAchievements")

            assertEquals("Couldn't reach RetroAchievements", menu.state.cheevos!!.pageError)
            assertNull(menu.state.cheevos!!.page)
        }

    @Test
    fun openingAgainFetchesAgain() =
        runTest {
            val menu = leaderboardsOpen()
            menu.openLeaderboard(31)
            runCurrent()
            menu.leaderboardFetched(menu.state.cheevos!!.request, sunnyPage, null)
            menu.closeLeaderboard()

            menu.openLeaderboard(31)
            runCurrent()

            assertEquals(2, calls.count { it.startsWith("fetchLeaderboard 31") })
            assertNull(menu.state.cheevos!!.page)
        }

    // --- The save-state hotkeys: the menu's own save path, without the menu. ---

    private fun TestScope.hotkey(
        menu: InGameMenu,
        which: StateHotkey,
    ) {
        menu.hotkey(which)
        runCurrent()
    }

    @Test
    fun saveHotkeySavesToTheLastSlot() =
        runTest {
            lastSlots.set(SERIAL, 4)
            val menu = menu()

            hotkey(menu, StateHotkey.Save)

            assertEquals(listOf("saveState $SERIAL 4", "message Saved to slot 4"), calls)
        }

    @Test
    fun saveHotkeyWithNoSlotYetSavesToSlot1() =
        runTest {
            val menu = menu()

            hotkey(menu, StateHotkey.Save)

            assertEquals(listOf("saveState $SERIAL 1", "message Saved to slot 1"), calls)
            assertEquals(1, lastSlots.get(SERIAL))
        }

    @Test
    fun nextWrapsAndSays() =
        runTest {
            lastSlots.set(SERIAL, 10)
            val menu = menu()

            hotkey(menu, StateHotkey.Next)
            assertEquals(listOf("message Slot 1 selected"), calls)
            calls.clear()
            hotkey(menu, StateHotkey.Save)

            assertEquals(listOf("saveState $SERIAL 1", "message Saved to slot 1"), calls)
        }

    @Test
    fun previousWraps() =
        runTest {
            lastSlots.set(SERIAL, 1)
            val menu = menu()

            hotkey(menu, StateHotkey.Previous)

            assertEquals(listOf("message Slot 10 selected"), calls)
        }

    @Test
    fun loadHotkeyLoadsTheSelectedSlot() =
        runTest {
            lastSlots.set(SERIAL, 3)
            val menu = menu()

            hotkey(menu, StateHotkey.Load)

            assertEquals(listOf("loadState $SERIAL 3", "message Loaded slot 3"), calls)
        }

    // Review Focus 5: Load in hardcore mode loads nothing, and says why.
    @Test
    fun loadHotkeyInHardcoreIsRefused() =
        runTest {
            api.hardcore = true
            val menu = menu()

            hotkey(menu, StateHotkey.Load)

            assertEquals(listOf("message Loading a state is off in hardcore mode"), calls)
        }

    // Review Focus 5: a game without a key keeps no states.
    @Test
    fun aGameWithNoKeyKeepsNoStates() =
        runTest {
            val menu = menu(booted = false)
            menu.gameStarted(game(""))
            runCurrent()
            calls.clear()

            hotkey(menu, StateHotkey.Save)
            hotkey(menu, StateHotkey.Next)

            assertEquals(List(2) { "message This game can't keep save states" }, calls)
        }

    @Test
    fun aFailedHotkeySaveSaysWhy() =
        runTest {
            api.saveError = "disk full"
            val menu = menu()

            hotkey(menu, StateHotkey.Save)

            assertEquals(listOf("saveState $SERIAL 1", "message Couldn't save to slot 1: disk full"), calls)
            assertNull(lastSlots.get(SERIAL))
        }

    @Test
    fun saveAndNextSavesThenSelectsTheNext() =
        runTest {
            lastSlots.set(SERIAL, 2)
            val menu = menu()

            hotkey(menu, StateHotkey.SaveAndNext)
            assertEquals(listOf("saveState $SERIAL 2", "message Saved to slot 2"), calls)
            calls.clear()
            hotkey(menu, StateHotkey.Save)

            assertEquals(listOf("saveState $SERIAL 3", "message Saved to slot 3"), calls)
        }

    // The menu's own save and load move the hotkeys' slot too, so Save never overwrites another slot than the last used.
    @Test
    fun theMenusLoadMovesTheHotkeysSlot() =
        runTest {
            val menu = menu()
            hotkey(menu, StateHotkey.Save)
            api.times[5] = 1_700_000_000L
            menu.openSlots(SlotMode.Load)
            runCurrent()
            menu.pickSlot(5)
            runCurrent()
            calls.clear()

            hotkey(menu, StateHotkey.Save)

            assertEquals(listOf("saveState $SERIAL 5", "message Saved to slot 5"), calls)
        }

    @Test
    fun undoSaysWhetherItDid() =
        runTest {
            val menu = menu()

            api.undoes = false
            hotkey(menu, StateHotkey.Undo)
            api.undoes = true
            hotkey(menu, StateHotkey.Undo)

            assertEquals(
                listOf("undoLoadState", "message No load to undo", "undoLoadState", "message Undid the last load"),
                calls,
            )
        }

    @Test
    fun noHotkeyBeforeTheBoot() =
        runTest {
            val menu = menu(booted = false)

            hotkey(menu, StateHotkey.Save)

            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun eachHotkeyHasTheCoresName() {
        assertEquals(
            listOf(
                "SaveSelectedSaveState",
                "LoadSelectedSaveState",
                "SelectPreviousSaveStateSlot",
                "SelectNextSaveStateSlot",
                "SaveStateAndSelectNextSlot",
                "UndoLoadState",
            ),
            StateHotkey.entries.map { it.coreName },
        )
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

    // --- The game's own settings ---

    @Test
    fun settingsOpenForTheRunningGamesKey() =
        runTest {
            val menu = menu()

            menu.openSettings()

            assertTrue(menu.state.settings)
            assertEquals(listOf("settings $SERIAL"), calls)
        }

    @Test
    fun settingsDontOpenBeforeTheBoot() =
        runTest {
            val menu = menu(booted = false)

            menu.openSettings()

            assertFalse(menu.state.settings)
            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun backFromSettingsIsTheMenuOnItsRow() =
        runTest {
            val menu = menu()
            menu.openSettings()
            calls.clear()

            menu.closeSettings()

            assertFalse(menu.state.settings)
            assertTrue(menu.state.cameFromSettings)
            assertEquals(listOf("settings null"), calls)
        }

    @Test
    fun modeResumesFromSettings() =
        runTest {
            val menu = menu()
            menu.openSettings()
            calls.clear()

            menu.resume()

            assertFalse(menu.state.settings)
            assertEquals(listOf("settings null", "resume"), calls)
        }

    // A game with no key (a PSF) keeps no settings of its own, as it keeps no states.
    @Test
    fun noSettingsForAGameWithoutAKey() =
        runTest {
            val menu = menu(booted = false)
            menu.gameStarted(game(""))
            runCurrent()
            calls.clear()

            menu.openSettings()

            assertFalse(menu.state.hasKey)
            assertFalse(menu.state.settings)
            assertFalse(calls.any { it.startsWith("settings") })
        }

    @Test
    fun theMenuOpensOnResumeAfterSettings() =
        runTest {
            val menu = menu()
            menu.openSettings()
            menu.closeSettings()

            menu.opened()

            assertFalse(menu.state.cameFromSettings)
        }

    // --- Shared slots ---

    private val crash = SaveStatePreview(null, 0, SERIAL, "CRASH", "content://games/crash.cue", "Crash Bandicoot")
    private val spyro = SaveStatePreview(null, 0, "SCUS-94228", "SPYRO", "content://games/spyro.cue", "Spyro")

    @Test
    fun theSlotPanelOpensOnThisGame() =
        runTest {
            api.times[1] = 100
            api.sharedTimes[4] = 400
            api.sharedHolds[4] = spyro
            val menu = menu()

            menu.openSlots(SlotMode.Load)
            runCurrent()

            val panel = menu.slots()
            assertEquals(SlotTab.ThisGame, panel.tab)
            assertTrue(panel.hasKey)
            assertEquals(listOf(1), panel.entries!!.map { it.slot })
            // Both tabs are read as it opens, so a switch shows at once.
            assertEquals(
                listOf(
                    SlotEntry(
                        4,
                        400L,
                        shared = true,
                        holds = HeldGame("Spyro", "SCUS-94228", "content://games/spyro.cue"),
                    ),
                ),
                panel.shared,
            )
            assertEquals(4, panel.sharedFocus)
        }

    @Test
    fun l1R1SwitchToSharedAndBack() =
        runTest {
            val menu = menu()
            menu.openSlots(SlotMode.Save)
            runCurrent()

            menu.switchTab()
            assertEquals(SlotTab.Shared, menu.slots().tab)
            menu.switchTab()
            assertEquals(SlotTab.ThisGame, menu.slots().tab)
        }

    @Test
    fun savingToAnEmptySharedSlotSaves() =
        runTest {
            val menu = menu()
            menu.openSlots(SlotMode.Save)
            runCurrent()
            menu.switchTab()
            calls.clear()

            menu.pickShared(2)
            runCurrent()

            assertEquals(listOf("saveSharedState 2", "message Saved to shared slot 2", "resume"), calls)
            assertNull(menu.state.slots)
            // A shared slot isn't the game's last slot.
            assertNull(lastSlots.get(SERIAL))
        }

    @Test
    fun aFailedSharedSaveSaysWhy() =
        runTest {
            api.saveError = "Disk full."
            val menu = menu()
            menu.openSlots(SlotMode.Save)
            runCurrent()
            menu.switchTab()

            menu.pickShared(2)
            runCurrent()

            assertEquals("Couldn't save to shared slot 2: Disk full.", menu.slots().error)
        }

    @Test
    fun replacingASharedSlotAsks() =
        runTest {
            api.sharedTimes[5] = 500
            api.sharedHolds[5] = spyro
            val menu = menu()
            menu.openSlots(SlotMode.Save)
            runCurrent()
            menu.switchTab()
            calls.clear()

            menu.pickShared(5)
            assertEquals(5, menu.slots().replacing)
            assertEquals(emptyList<String>(), calls)
            // The question can't move to the other tab.
            menu.switchTab()
            assertEquals(SlotTab.Shared, menu.slots().tab)

            menu.confirmReplace()
            runCurrent()
            assertEquals(listOf("saveSharedState 5", "message Saved to shared slot 5", "resume"), calls)
        }

    @Test
    fun loadingThisGamesSharedSlotLoads() =
        runTest {
            api.sharedTimes[3] = 300
            api.sharedHolds[3] = crash
            val menu = menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            menu.switchTab()
            calls.clear()

            menu.pickShared(3)
            runCurrent()

            assertEquals(listOf("loadSharedState 3", "message Loaded shared slot 3", "resume"), calls)
            assertNull(lastSlots.get(SERIAL))
        }

    @Test
    fun aSharedPickBeforeItsPreviewReadsItFirst() =
        runTest {
            api.sharedTimes[3] = 300
            val menu = menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            // Its preview wasn't readable as the panel opened; now it is.
            assertNull(
                menu
                    .slots()
                    .shared!!
                    .single()
                    .holds,
            )
            api.sharedHolds[3] = crash
            menu.switchTab()
            calls.clear()

            menu.pickShared(3)
            runCurrent()

            assertEquals(
                listOf(
                    "getStatePreview shared 3 content://games/crash.cue",
                    "loadSharedState 3",
                    "message Loaded shared slot 3",
                    "resume",
                ),
                calls,
            )
        }

    @Test
    fun anUnreadableSharedSlotIsntLoaded() =
        runTest {
            api.sharedTimes[3] = 300
            val menu = menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            menu.switchTab()
            calls.clear()

            menu.pickShared(3)
            runCurrent()

            assertTrue(calls.none { it.startsWith("loadSharedState") })
            assertEquals("Couldn't read that save.", menu.slots().error)
        }

    // --- Delete ---

    private val acDiscs =
        listOf(
            GameDisc("content://games/ac3 (disc 1).chd", "SLPS-02020", "AC3 (Disc 1)", 1),
            GameDisc("content://games/ac3 (disc 2).chd", "SLPS-02021", "AC3 (Disc 2)", 2),
        )

    private suspend fun TestScope.loadPanel(menu: InGameMenu) {
        menu.openSlots(SlotMode.Load)
        runCurrent()
        calls.clear()
    }

    @Test
    fun xAsksBeforeDeleting() =
        runTest {
            api.times[3] = 300
            val menu = menu()
            loadPanel(menu)

            menu.askDelete(menu.entries().single())
            assertEquals(SlotEntry(3, 300L), menu.slots().deleting)
            assertEquals(emptyList<String>(), calls)

            menu.cancelDelete()
            assertNull(menu.slots().deleting)
            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun saveDoesntDelete() =
        runTest {
            api.times[3] = 300
            val menu = menu()
            menu.openSlots(SlotMode.Save)
            runCurrent()

            menu.askDelete(menu.entries().first { it.slot == 3 })
            assertNull(menu.slots().deleting)
        }

    @Test
    fun aDeleteRemovesTheSaveItAskedAbout() =
        runTest {
            api.times[3] = 300
            api.times[5] = 500
            val menu = menu()
            loadPanel(menu)

            menu.askDelete(menu.entries().first { it.slot == 3 })
            // A save comes in under the question (desktop's, through the data folder): the delete is still slot 3's.
            api.times[2] = 200
            menu.confirmDelete()
            runCurrent()

            assertEquals("deleteState $SERIAL 3", calls.first())
            assertNull(menu.slots().deleting)
            assertEquals(listOf(2, 5), menu.entries().map { it.slot })
            // The panel stays open, on the next row.
            assertEquals(5, menu.slots().focusRow?.slot)
            assertTrue(calls.none { it == "resume" })
        }

    @Test
    fun deletingAnOtherDiscsSaveUsesItsSerial() =
        runTest {
            api.serialTimes["SLPS-02021"] = mapOf(4 to 400L)
            val menu = menu(booted = false)
            menu.gameStarted(game("SLPS-02020").copy(discs = acDiscs))
            runCurrent()
            loadPanel(menu)

            menu.askDelete(menu.slots().others.single())
            menu.confirmDelete()
            runCurrent()

            assertEquals("deleteState SLPS-02021 4", calls.first())
            assertEquals(emptyList<SlotEntry>(), menu.slots().others)
        }

    @Test
    fun deletingASharedSlot() =
        runTest {
            api.sharedTimes[6] = 600
            val menu = menu()
            loadPanel(menu)
            menu.switchTab()

            menu.askDelete(menu.slots().shared!!.single())
            menu.confirmDelete()
            runCurrent()

            assertEquals("deleteState shared 6", calls.first())
            assertEquals(emptyList<SlotEntry>(), menu.slots().shared)
            assertEquals(SlotTab.Shared, menu.slots().tab)
        }

    @Test
    fun aFailedDeleteSaysWhy() =
        runTest {
            api.times[3] = 300
            api.deleteError = "Permission denied."
            val menu = menu()
            loadPanel(menu)

            menu.askDelete(menu.entries().single())
            menu.confirmDelete()
            runCurrent()

            assertEquals("Couldn't delete that save: Permission denied.", menu.slots().error)
            assertEquals(listOf(3), menu.entries().map { it.slot })
        }

    // --- Undo load ---

    @Test
    fun canUndoIsReadAsTheMenuOpens() =
        runTest {
            val menu = menu()
            assertFalse(menu.state.canUndo)

            api.canUndo = true
            menu.opened()
            runCurrent()
            assertTrue(menu.state.canUndo)
        }

    @Test
    fun undoLoadUndoesAndResumes() =
        runTest {
            api.canUndo = true
            val menu = menu()
            menu.opened()
            runCurrent()
            calls.clear()

            menu.undoLoad()
            runCurrent()

            assertEquals(listOf("undoLoadState", "message Undid the last load", "resume"), calls)
        }

    @Test
    fun undoLoadSaysWhenThereWasNone() =
        runTest {
            api.canUndo = true
            api.undoes = false
            val menu = menu()
            menu.opened()
            runCurrent()
            calls.clear()

            menu.undoLoad()
            runCurrent()

            assertEquals(listOf("undoLoadState", "message No load to undo", "resume"), calls)
        }

    @Test
    fun undoLoadIsOffInHardcore() =
        runTest {
            api.canUndo = true
            api.hardcore = true
            val menu = menu()
            menu.opened()
            runCurrent()
            calls.clear()

            menu.undoLoad()
            runCurrent()

            assertTrue(calls.none { it == "undoLoadState" })
        }

    @Test
    fun theUndoHotkeyIsRefusedInHardcore() =
        runTest {
            api.hardcore = true
            val menu = menu()
            menu.opened()
            runCurrent()
            calls.clear()

            menu.hotkey(StateHotkey.Undo)
            runCurrent()

            assertEquals(listOf("message Loading a state is off in hardcore mode"), calls)
        }

    // --- A shared slot of another game ---

    private suspend fun TestScope.sharedLoad(
        menu: InGameMenu,
        holds: SaveStatePreview = spyro,
    ) {
        api.sharedTimes[3] = 300
        api.sharedHolds[3] = holds
        menu.openSlots(SlotMode.Load)
        runCurrent()
        menu.switchTab()
        calls.clear()
    }

    @Test
    fun anotherGamesSharedSlotAsksFirst() =
        runTest {
            val menu = menu()
            sharedLoad(menu)

            menu.pickShared(3)
            runCurrent()

            assertEquals(3, menu.slots().switching?.slot)
            assertEquals(
                "Load Spyro from shared slot 3? This game stops.",
                SaveLists.switchQuestion(menu.slots().switching!!),
            )
            assertTrue(calls.none { it.startsWith("loadSharedState") || it.startsWith("exit") })

            menu.cancelSwitch()
            assertNull(menu.slots().switching)
        }

    @Test
    fun confirmingTheSwitchExitsThenStartsIt() =
        runTest {
            val menu = menu()
            sharedLoad(menu)
            menu.pickShared(3)
            runCurrent()

            menu.confirmSwitch()
            runCurrent()

            assertNull(menu.state.slots)
            assertEquals(
                listOf(
                    "savesResumeState",
                    "getSaveStateTimes $SERIAL",
                    "saveResumeState $SERIAL",
                    "switch content://games/spyro.cue 3",
                    "exit null",
                ),
                calls,
            )
        }

    @Test
    fun theSwitchAsksToReplaceTheResumeState() =
        runTest {
            api.times[SaveSlots.RESUME] = 900
            val menu = menu()
            sharedLoad(menu)
            menu.pickShared(3)
            runCurrent()

            menu.confirmSwitch()
            runCurrent()
            assertEquals(900L, menu.state.replacingResume)

            menu.keepResume()
            assertEquals(listOf("switch content://games/spyro.cue 3", "exit null"), calls.takeLast(2))
        }

    @Test
    fun theSwitchWarnsWhileACardIsWritten() =
        runTest {
            api.cardBusy = true
            val menu = menu()
            menu.opened()
            runCurrent()
            sharedLoad(menu)
            menu.pickShared(3)
            runCurrent()

            menu.confirmSwitch()
            runCurrent()
            assertTrue(menu.state.confirmingExit)
            assertTrue(calls.none { it.startsWith("switch") })

            menu.confirmExit()
            runCurrent()
            assertEquals(listOf("switch content://games/spyro.cue 3", "exit null"), calls.takeLast(2))
        }

    @Test
    fun aCancelledSwitchIsForgotten() =
        runTest {
            api.times[SaveSlots.RESUME] = 900
            val menu = menu()
            sharedLoad(menu)
            menu.pickShared(3)
            runCurrent()
            menu.confirmSwitch()
            runCurrent()

            // B on the resume question stays in the game.
            menu.cancelReplaceResume()
            api.times.remove(SaveSlots.RESUME)
            calls.clear()

            menu.exit()
            runCurrent()
            assertTrue(calls.none { it.startsWith("switch") })
            assertEquals("exit null", calls.last())
        }

    @Test
    fun aSwitchIsRefusedWhenHardcoreWouldStart() =
        runTest {
            api.hardcoreWouldStart = true
            val menu = menu()
            sharedLoad(menu)

            menu.pickShared(3)
            runCurrent()

            assertNull(menu.slots().switching)
            assertEquals("Loading a state is off in hardcore mode", menu.slots().error)
        }

    @Test
    fun aGameNotInTheLibraryIsNamed() =
        runTest {
            val menu = menu()
            sharedLoad(menu, SaveStatePreview(null, 0, "SCUS-94228", "Spyro the Dragon", "", ""))

            menu.pickShared(3)
            runCurrent()

            assertNull(menu.slots().switching)
            assertEquals(
                "Shared slot 3 needs Spyro the Dragon (SCUS-94228), which isn't in your library.",
                menu.slots().error,
            )
        }

    // Review: a resume save that fails stops the switch, so the library says the place wasn't saved.
    @Test
    fun aFailedResumeSaveStopsTheSwitch() =
        runTest {
            api.resumeError = "No space left on device."
            val menu = menu()
            sharedLoad(menu)
            menu.pickShared(3)
            runCurrent()

            menu.confirmSwitch()
            runCurrent()

            assertTrue(calls.none { it.startsWith("switch") })
            assertEquals("exit No space left on device.", calls.last())
        }

    // PR review: a second A while an unread shared slot's game is read doesn't load it twice (the second load would take
    // the undo state).
    @Test
    fun aDoubleAOnAnUnreadSharedSlotLoadsOnce() =
        runTest {
            api.sharedTimes[3] = 300
            val menu = menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            api.sharedHolds[3] = crash
            menu.switchTab()
            calls.clear()

            menu.pickShared(3)
            menu.pickShared(3)
            runCurrent()

            assertEquals(1, calls.count { it == "loadSharedState 3" })
            assertEquals(1, calls.count { it.startsWith("getStatePreview shared 3") })
        }

    // A refused pick leaves the panel ready for another.
    @Test
    fun aRefusedSharedPickLeavesThePanelReady() =
        runTest {
            api.sharedTimes[3] = 300
            val menu = menu()
            menu.openSlots(SlotMode.Load)
            runCurrent()
            menu.switchTab()

            menu.pickShared(3)
            runCurrent()

            assertEquals("Couldn't read that save.", menu.slots().error)
            assertFalse(menu.slots().busy)
        }
}
