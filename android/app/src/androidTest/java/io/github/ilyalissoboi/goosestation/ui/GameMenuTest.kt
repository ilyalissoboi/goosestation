// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.AchievementsPanelState
import io.github.ilyalissoboi.goosestation.app.CheevoTab
import io.github.ilyalissoboi.goosestation.app.CodePanel
import io.github.ilyalissoboi.goosestation.app.DiscChoice
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.GameMenuState
import io.github.ilyalissoboi.goosestation.app.PatchCodeGroup
import io.github.ilyalissoboi.goosestation.app.PatchCodeRow
import io.github.ilyalissoboi.goosestation.app.SaveSlots
import io.github.ilyalissoboi.goosestation.app.SlotEntry
import io.github.ilyalissoboi.goosestation.app.SlotMode
import io.github.ilyalissoboi.goosestation.app.SlotPanel
import io.github.ilyalissoboi.goosestation.core.Achievement
import io.github.ilyalissoboi.goosestation.core.AchievementSection
import io.github.ilyalissoboi.goosestation.core.GameAchievements
import io.github.ilyalissoboi.goosestation.core.GameDisc
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.Leaderboard
import io.github.ilyalissoboi.goosestation.core.LeaderboardEntry
import io.github.ilyalissoboi.goosestation.core.LeaderboardPage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The in-game menu with a controller: Resume first, the rows in order, the slot panel beside it, the confirmations in
 * the panels, and B stepping out one layer at a time, acting only on presses it saw.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GameMenuTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = ZonedDateTime.of(2026, 10, 31, 14, 5, 0, 0, ZoneId.of("Asia/Tokyo"))
    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(GameMenuState(booted = true))

    // Whether the fake opens the Achievements panel and switches its tab, as InGameMenu does; with [listsLoad], the
    // Leaderboards tab opens without its list, which the test then gives it, as the core's read does.
    private var switchingTabs = false
    private var listsLoad = false

    // Records each call; closing a layer closes it in the state too, as InGameMenu does.
    private val actions =
        object : GameMenuActions {
            override fun resume() {
                calls += "resume"
            }

            override fun openSlots(mode: SlotMode) {
                calls += "openSlots $mode"
            }

            override fun closeSlots() {
                calls += "closeSlots"
                state = state.copy(slots = null)
            }

            override fun pickSlot(slot: Int) {
                calls += "pickSlot $slot"
            }

            override fun pickOther(
                serial: String,
                slot: Int,
            ) {
                calls += "pickOther $serial $slot"
            }

            override fun openDiscs() {
                calls += "openDiscs"
            }

            override fun closeDiscs() {
                calls += "closeDiscs"
                state = state.copy(discs = null)
            }

            override fun pickDisc(number: Int) {
                calls += "pickDisc $number"
                // The disc already in just closes the panel, as InGameMenu does.
                if (number == state.discs?.current) state = state.copy(discs = null)
            }

            override fun openCodes() {
                calls += "openCodes"
            }

            override fun closeCodes() {
                calls += "closeCodes"
                state = state.copy(codes = null)
            }

            override fun setCode(
                index: Int,
                on: Boolean,
            ) {
                calls += "setCode $index $on"
            }

            override fun applyCode(index: Int) {
                calls += "applyCode $index"
            }

            override fun confirmReplace() {
                calls += "confirmReplace"
            }

            override fun cancelReplace() {
                calls += "cancelReplace"
                state = state.copy(slots = state.slots?.copy(replacing = null))
            }

            override fun toggleFastForward() {
                calls += "toggleFastForward"
            }

            override fun toggleAnalogMode() {
                calls += "toggleAnalogMode"
            }

            override fun screenshot() {
                calls += "screenshot"
            }

            override fun askReset() {
                calls += "askReset"
            }

            override fun confirmReset() {
                calls += "confirmReset"
            }

            override fun cancelReset() {
                calls += "cancelReset"
                state = state.copy(confirmingReset = false)
            }

            override fun exit() {
                calls += "exit"
            }

            override fun confirmExit() {
                calls += "confirmExit"
            }

            override fun cancelExit() {
                calls += "cancelExit"
                state = state.copy(confirmingExit = false)
            }

            override fun replaceResume() {
                calls += "replaceResume"
            }

            override fun keepResume() {
                calls += "keepResume"
            }

            override fun cancelReplaceResume() {
                calls += "cancelReplaceResume"
                state = state.copy(replacingResume = null)
            }

            override fun openAchievements() {
                calls += "openAchievements"
                if (switchingTabs) {
                    state =
                        state.copy(cheevos = AchievementsPanelState(CheevoTab.Achievements, sections))
                }
            }

            override fun closeAchievements() {
                calls += "closeAchievements"
                state = state.copy(cheevos = null)
            }

            override fun switchTab() {
                calls += "switchTab"
                if (switchingTabs) {
                    val list = if (listsLoad) null else boards
                    state = state.copy(cheevos = AchievementsPanelState(CheevoTab.Leaderboards, leaderboards = list))
                }
            }

            override fun openLeaderboard(id: Int) {
                calls += "openLeaderboard $id"
            }

            override fun closeLeaderboard() {
                calls += "closeLeaderboard"
                state = state.copy(cheevos = state.cheevos?.copy(open = null, page = null, pageError = null))
            }
        }

    private fun saved(vararg slots: Int): List<SlotEntry> =
        (1..SaveSlots.COUNT).map { SlotEntry(it, if (it in slots) now.toEpochSecond() - 600 else null) }

    private fun show(
        game: GameEntry? = null,
        width: Int = 960,
        height: Int = 540,
    ) {
        rule.setContent {
            GooseStationTheme {
                Box(Modifier.requiredSize(width.dp, height.dp)) { GameMenu(state, actions, game, now) }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun rowsAreInOrderAndResumeIsFocused() {
        state = GameMenuState(booted = true, analogMode = false)
        show()

        rule.onNodeWithText("PAUSED").assertExists()
        rule.onNodeWithText("Resume").assertIsFocused()
        val order =
            listOf(
                "Save state",
                "Load state",
                "Fast forward",
                "Analog mode",
                "Screenshot",
                "Patch codes",
                "Reset console",
                "Exit game",
            )
        for (label in order) {
            press(Key.DirectionDown)
            rule.onNode(isFocused() and hasText(label, substring = true)).assertExists()
        }
    }

    @Test
    fun hardcoreRowsAreDisabledWithTheReason() {
        state = GameMenuState(booted = true, analogMode = false, hardcore = true)
        show()

        for (row in listOf("Load state", "Fast forward", "Patch codes")) {
            rule.onNode(hasText(row) and hasText("Off in hardcore mode")).assertIsNotEnabled()
        }
        // The D-pad passes over them: from Save state, Down reaches Analog mode.
        press(Key.DirectionDown)
        rule.onNode(isFocused()).assert(hasText("Save state"))
        press(Key.DirectionDown)
        rule.onNode(isFocused()).assert(hasText("Analog mode"))
    }

    private val ready = GameAchievements(GameAchievements.READY, 14, 40, 85, 400, true, true, false)

    private fun cheevo(
        id: Int,
        title: String,
        unlockedAt: Long = 0,
        progress: String = "",
    ) = Achievement(id, title, "Do the thing $id", 5, "", unlockedAt, progress, if (progress.isEmpty()) 0f else 62f)

    private val sections =
        listOf(
            AchievementSection(7, "Almost there", arrayOf(cheevo(1, "Gem Collector", progress = "312/500"))),
            AchievementSection(1, "Locked", arrayOf(cheevo(2, "Flawless Flight"), cheevo(3, "Dragon Master"))),
            AchievementSection(2, "Unlocked", arrayOf(cheevo(4, "Sparx Rescue", now.toEpochSecond() - 600))),
        )

    private fun showAchievements(game: GameAchievements = ready) {
        state =
            GameMenuState(
                booted = true,
                achievements = game,
                cheevos = AchievementsPanelState(CheevoTab.Achievements, sections),
            )
        show()
    }

    @Test
    fun theAchievementsRowShowsTheProgress() {
        state = GameMenuState(booted = true, achievements = ready)
        show()

        rule.onNode(hasText("Achievements") and hasText("14 of 40")).assertIsEnabled()
    }

    @Test
    fun theAchievementsRowSaysWhyItsOff() {
        state =
            GameMenuState(
                booted = true,
                achievements = GameAchievements(GameAchievements.OFF, 0, 0, 0, 0, false, false, false),
            )
        show()

        rule.onNode(hasText("Achievements") and hasText("Sign in from Settings")).assertIsNotEnabled()
    }

    @Test
    fun achievementsOpenFocusedOnTheFirstRow() {
        showAchievements()

        rule.onNodeWithText("14 of 40 unlocked").assertExists()
        rule.onNodeWithText("85 of 400 points").assertExists()
        rule.onNode(isFocused()).assert(hasText("Gem Collector"))
    }

    @Test
    fun theDpadScrollsThroughTheSections() {
        showAchievements()

        for (title in listOf("Flawless Flight", "Dragon Master", "Sparx Rescue")) {
            press(Key.DirectionDown)
            rule.onNode(isFocused()).assert(hasText(title)).assertIsDisplayed()
        }
        rule.onNodeWithText("ALMOST THERE").assertExists()
    }

    @Test
    fun l1AndR1SwitchTabs() {
        showAchievements()

        press(Key.ButtonL1)
        press(Key.ButtonR1)

        assertEquals(listOf("switchTab", "switchTab"), calls)
    }

    @Test
    fun bClosesTheAchievementsToTheirRow() {
        showAchievements()

        press(Key.ButtonB)

        assertEquals(listOf("closeAchievements"), calls)
        rule.onNode(isFocused()).assert(hasText("Achievements"))
    }

    @Test
    fun oneTabShowsNoTabs() {
        showAchievements(ready.let { GameAchievements(it.status, 14, 40, 85, 400, true, false, false) })

        rule.onNodeWithText("Leaderboards").assertDoesNotExist()
    }

    @Test
    fun progressShowsItsCountAndLockedRowsSaySo() {
        showAchievements()

        rule.onNode(hasText("Gem Collector") and hasText("312/500")).assertExists()
        rule.onNode(hasText("Flawless Flight") and hasStateDescription("Locked")).assertExists()
        rule.onNode(hasText("Sparx Rescue") and hasStateDescription("Unlocked")).assertExists()
    }

    private val boards =
        listOf(Leaderboard(31, "Sunny Flight", "Fastest time"), Leaderboard(32, "Gem Rush", "Most gems"))

    private fun entry(rank: Int) = LeaderboardEntry(rank, "user$rank", "1:0$rank")

    private val sunnyPage =
        LeaderboardPage(
            1284,
            (1..10).map(::entry).toTypedArray(),
            (208..216).map(::entry).toTypedArray(),
            4,
        )

    private fun showLeaderboards(
        open: Leaderboard? = null,
        page: LeaderboardPage? = null,
        error: String? = null,
    ) {
        state =
            GameMenuState(
                booted = true,
                achievements = ready,
                cheevos =
                    AchievementsPanelState(
                        CheevoTab.Leaderboards,
                        leaderboards = boards,
                        open = open,
                        page = page,
                        pageError = error,
                    ),
            )
        show()
    }

    // Switching tabs takes away the focused row: the menu keeps its Achievements row in view, current.
    @Test
    fun switchingTabsKeepsTheMenusRowInView() {
        switchingTabs = true
        state = GameMenuState(booted = true, analogMode = true, achievements = ready)
        // A phone in landscape, the emulator's screen: the menu scrolls.
        show(width = 640, height = 360)
        repeat(12) {
            if (rule.onAllNodes(isFocused() and hasText("Achievements")).fetchSemanticsNodes().isEmpty()) {
                press(Key.DirectionDown)
            }
        }
        rule.onNode(isFocused()).assert(hasText("Achievements"))
        press(Key.ButtonA)
        rule.onNode(isFocused()).assert(hasText("Gem Collector"))

        press(Key.ButtonR1)

        rule.onNode(isFocused()).assert(hasText("Sunny Flight"))
        rule.onNode(hasText("Achievements") and hasText("14 of 40")).assertIsDisplayed()
    }

    // Found on the Thor: the heading held the focus while the list loaded, then stopped being focusable, and the focus
    // fell to the menu's top.
    @Test
    fun aListThatLoadsKeepsTheMenusRowInView() {
        switchingTabs = true
        listsLoad = true
        state = GameMenuState(booted = true, analogMode = true, achievements = ready)
        show(width = 640, height = 360)
        repeat(12) {
            if (rule.onAllNodes(isFocused() and hasText("Achievements")).fetchSemanticsNodes().isEmpty()) {
                press(Key.DirectionDown)
            }
        }
        press(Key.ButtonA)
        press(Key.ButtonR1)
        rule.onNodeWithText("Loading…").assertExists()

        state = state.copy(cheevos = state.cheevos!!.copy(leaderboards = boards))
        rule.waitForIdle()

        rule.onNode(isFocused()).assert(hasText("Sunny Flight"))
        rule.onNode(hasText("Achievements") and hasText("14 of 40")).assertIsDisplayed()
    }

    // Found on the Thor: switching tabs passes the focus through the menu's row for a frame, and its ring flashed there.
    // While a side panel is open the menu's rows draw none.
    @Test
    fun aMenuRowShowsNoRingWhileAPanelIsOpen() {
        switchingTabs = true
        state = GameMenuState(booted = true, achievements = ready)
        var focus = Color.Unspecified
        rule.setContent {
            GooseStationTheme {
                focus = GooseStationTheme.colors.focus
                // The window's own size: the pixels are read from it.
                Box(Modifier.fillMaxSize()) { GameMenu(state, actions, null, now) }
            }
        }
        rule.waitForIdle()
        repeat(12) {
            if (rule.onAllNodes(isFocused() and hasText("Achievements")).fetchSemanticsNodes().isEmpty()) {
                press(Key.DirectionDown)
            }
        }
        val row = rule.onNode(hasText("Achievements") and hasText("14 of 40"))

        // 3 dp above the row, halfway across: the middle of a ring around it.
        fun ring(): Boolean {
            val bounds = row.getUnclippedBoundsInRoot()
            val x = with(rule.density) { ((bounds.left + bounds.right) / 2).roundToPx() }
            val y = with(rule.density) { (bounds.top - 3.dp).roundToPx() }
            return rule.onRoot().captureToImage().toPixelMap()[x, y] == focus
        }
        assertTrue(ring())

        press(Key.ButtonA)
        rule.onNode(isFocused()).assert(hasText("Gem Collector"))
        row.requestFocus()
        rule.waitForIdle()

        row.assertIsFocused()
        assertFalse(ring())
    }

    @Test
    fun aLeaderboardOpensWithA() {
        showLeaderboards()

        rule.onNode(isFocused()).assert(hasText("Sunny Flight"))
        press(Key.DirectionDown)
        press(Key.ButtonA)

        assertEquals(listOf("openLeaderboard 32"), calls)
    }

    @Test
    fun anOpenLeaderboardFocusesYourRow() {
        showLeaderboards(boards[0], sunnyPage)

        rule.onNodeWithText("1,284 entries").assertExists()
        rule.onNodeWithText("AROUND YOU").assertExists()
        rule.onNode(isFocused()).assert(hasText("user212")).assert(hasStateDescription("You"))
    }

    @Test
    fun bBackFromALeaderboardFocusesItsRow() {
        showLeaderboards(boards[1], sunnyPage)

        press(Key.ButtonB)

        assertEquals(listOf("closeLeaderboard"), calls)
        rule.onNode(isFocused()).assert(hasText("Gem Rush"))
        press(Key.ButtonB)
        assertEquals(listOf("closeLeaderboard", "closeAchievements"), calls)
    }

    @Test
    fun aLeaderboardIsLoadingUntilItsPageArrives() {
        showLeaderboards(boards[0])

        rule.onNodeWithText("Loading…").assertExists()
        state = state.copy(cheevos = state.cheevos!!.copy(page = sunnyPage))
        rule.waitForIdle()
        rule.onNodeWithText("user1").assertExists()
    }

    @Test
    fun aLeaderboardThatCouldntLoadSaysWhy() {
        showLeaderboards(boards[0], error = "Couldn't reach RetroAchievements")

        rule.onNodeWithText("Couldn't reach RetroAchievements").assertExists()
    }

    @Test
    fun analogModeIsHiddenForAnotherController() {
        state = GameMenuState(booted = true, analogMode = null)
        show()

        rule.onNodeWithText("Analog mode").assertDoesNotExist()
    }

    @Test
    fun fastForwardAndAnalogModeShowTheirValues() {
        state = GameMenuState(booted = true, fastForward = true, analogMode = false)
        show()

        rule.onNodeWithText("On").assertExists()
        rule.onNodeWithText("Off").assertExists()
    }

    @Test
    fun itNamesTheGame() {
        show(
            GameEntry(
                path = "content://test/tree/games/document/crash.cue",
                title = "Crash Bandicoot",
                serial = "SCUS-94900",
                region = "NTSC-U",
                totalPlayedSeconds = 3 * 3600 + 12 * 60,
                lastPlayedSeconds = 0,
                coverPath = null,
            ),
        )

        rule.onNodeWithText("Crash Bandicoot").assertExists()
        rule.onNodeWithText("NTSC-U · SCUS-94900 · 3h 12m").assertExists()
    }

    @Test
    fun gameRowsWaitForTheBoot() {
        state = GameMenuState(booted = false)
        show()

        rule.onNodeWithText("Starting…").assertExists()
        rule.onNodeWithText("Save state").assertIsNotEnabled()
        press(Key.DirectionDown)
        rule.onNodeWithText("Exit game").assertIsFocused()
    }

    @Test
    fun rowsCallTheirActions() {
        state = GameMenuState(booted = true, analogMode = true)
        show()

        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)

        assertEquals(
            listOf(
                "openSlots Save",
                "openSlots Load",
                "toggleFastForward",
                "toggleAnalogMode",
                "screenshot",
                "openCodes",
                "askReset",
                "exit",
            ),
            calls,
        )
    }

    @Test
    fun saveStateOpensThePanelOnTheFocusedSlot() {
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Save, saved(3), focus = 3))
        show()

        rule.onNode(isFocused() and hasText("Slot 3", substring = true)).assertExists()
        rule.onNode(hasText("Save state") and hasClickAction()).assertIsSelected()
        rule.onNodeWithText("Today · 13:55", substring = true).assertExists()
        rule.onNodeWithContentDescription("A: Save").assertExists()
    }

    @Test
    fun aSlotIsPicked() {
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Save, saved(), focus = 1))
        show()

        press(Key.DirectionDown)
        press(Key.Enter)

        assertEquals(listOf("pickSlot 2"), calls)
    }

    @Test
    fun loadListsTheSavedSlots() {
        val entries = listOf(SlotEntry(SaveSlots.RESUME, now.toEpochSecond())) + saved(4).filter { it.savedAt != null }
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Load, entries, focus = SaveSlots.RESUME))
        show()
        rule
            .onNode(isFocused() and hasText("Resume", substring = true) and hasText("Saved", substring = true))
            .assertExists()

        press(Key.DirectionDown)

        rule.onNode(isFocused() and hasText("Slot 4", substring = true)).assertExists()
        rule.onNodeWithText("Slot 1").assertDoesNotExist()
    }

    @Test
    fun otherSavesShowUnderTheirHeading() {
        val others = listOf(SlotEntry(4, now.toEpochSecond() - 600, owner = "SLPS-02021", disc = 2))
        state =
            GameMenuState(booted = true, slots = SlotPanel(SlotMode.Load, saved(1).take(1), focus = 1, others = others))
        show()

        rule.onNodeWithText("OTHER SAVES").assertExists()
        press(Key.DirectionDown)
        rule.onNode(isFocused() and hasText("Slot 4", substring = true)).assertExists()
        press(Key.Enter)
        assertEquals(listOf("pickOther SLPS-02021 4"), calls)
    }

    @Test
    fun onlyOtherSavesAreStillSaves() {
        val others = listOf(SlotEntry(SaveSlots.RESUME, now.toEpochSecond() - 600, owner = "SLPS-02021", disc = 2))
        state =
            GameMenuState(booted = true, slots = SlotPanel(SlotMode.Load, emptyList(), focus = null, others = others))
        show()

        rule.onNodeWithText("No saves yet for this game.").assertDoesNotExist()
        rule.onNode(isFocused() and hasText("Resume", substring = true)).assertExists()
    }

    @Test
    fun aRowShowsItsDisc() {
        val entries = listOf(SlotEntry(1, now.toEpochSecond() - 600, disc = 2))
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Load, entries, focus = 1))
        show()

        rule.onNodeWithText("Saved · Disc 2").assertExists()
    }

    private val ac3Discs =
        listOf(
            GameDisc("content://g/ac3 1.chd", "SLPS-02020", "Ace Combat 3 (Disc 1)", 1),
            GameDisc("content://g/ac3 2.chd", "SLPS-02021", "Ace Combat 3 (Disc 2)", 2),
        )

    @Test
    fun changeDiscSitsAfterLoadState() {
        state = GameMenuState(booted = true, disc = 1, discCount = 2)
        show()

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        rule.onNode(isFocused() and hasText("Change disc", substring = true)).assertExists()
        rule.onNodeWithText("Disc 1 of 2").assertExists()
        press(Key.Enter)
        assertEquals(listOf("openDiscs"), calls)
    }

    @Test
    fun aSingleDiscGameHasNoChangeDisc() {
        show()

        rule.onNodeWithText("Change disc").assertDoesNotExist()
    }

    @Test
    fun theDiscPanelFocusesTheCurrentDisc() {
        state = GameMenuState(booted = true, disc = 2, discCount = 2, discs = DiscChoice(ac3Discs, current = 2))
        show()

        rule.onNode(isFocused() and hasText("Disc 2", substring = true)).assertExists()
        rule.onNodeWithText("Current").assertExists()
        rule.onNode(hasText("Change disc") and hasClickAction()).assertIsSelected()
        rule.onNodeWithText("SLPS-02021").assertDoesNotExist()
        rule.onNodeWithText("Ace Combat 3 (Disc 2)").assertExists()

        press(Key.DirectionUp)
        press(Key.Enter)
        assertEquals(listOf("pickDisc 1"), calls)
    }

    @Test
    fun aDiscWithTheGamesTitleShowsItsSerial() {
        val discs = ac3Discs.map { it.copy(title = "Ace Combat 3") }
        state = GameMenuState(booted = true, disc = 1, discCount = 2, discs = DiscChoice(discs, current = 1))
        show(game = GameEntry("content://g/ac3 1.chd", "Ace Combat 3", "SLPS-02020", "NTSC-J", 0, 0, null, discs))

        rule.onNodeWithText("SLPS-02021").assertExists()
    }

    @Test
    fun bLeavesTheDiscPanelForItsRow() {
        state = GameMenuState(booted = true, disc = 1, discCount = 2, discs = DiscChoice(ac3Discs, current = 1))
        show()

        press(Key.ButtonB)

        assertEquals(listOf("closeDiscs"), calls)
        rule.onNodeWithText("Change disc").assertIsFocused()
    }

    @Test
    fun theDiscPanelsHintsSayInsert() {
        state = GameMenuState(booted = true, disc = 1, discCount = 2, discs = DiscChoice(ac3Discs, current = 1))
        show()

        rule.onNodeWithContentDescription("A: Insert").assertExists()
    }

    @Test
    fun aFailedSwapShowsInThePanel() {
        val choice = DiscChoice(ac3Discs, current = 1, error = "Disc 2 isn't in your library any more.")
        state = GameMenuState(booted = true, disc = 1, discCount = 2, discs = choice)
        show()

        rule.onNodeWithText("Disc 2 isn't in your library any more.").assertExists()
    }

    private fun codes(vararg groups: PatchCodeGroup) = CodePanel(groups.toList(), "Crash Bandicoot.cht")

    @Test
    fun patchCodesSitsAfterScreenshot() {
        show()

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        rule.onNode(isFocused() and hasText("Patch codes", substring = true)).assertExists()
        press(Key.Enter)
        assertEquals(listOf("openCodes"), calls)
    }

    @Test
    fun aCodeIsASwitchRow() {
        state =
            GameMenuState(
                booted = true,
                codes = codes(PatchCodeGroup(null, listOf(PatchCodeRow(4, "Infinite HP", "In battle", false, false)))),
            )
        show()

        rule.onNode(isFocused() and hasText("Infinite HP", substring = true)).assertIsOff()
        rule.onNodeWithText("In battle").assertExists()
        press(Key.Enter)
        assertEquals(listOf("setCode 4 true"), calls)
    }

    @Test
    fun aManualCodeHasApply() {
        state =
            GameMenuState(
                booted = true,
                codes = codes(PatchCodeGroup(null, listOf(PatchCodeRow(2, "Max Money Now", null, true, false)))),
            )
        show()

        rule.onNodeWithText("Apply").assertExists()
        press(Key.Enter)
        assertEquals(listOf("applyCode 2"), calls)
    }

    @Test
    fun groupsShowTheirHeadings() {
        state =
            GameMenuState(
                booted = true,
                codes =
                    codes(
                        PatchCodeGroup("Player", listOf(PatchCodeRow(0, "Infinite HP", null, false, true))),
                        PatchCodeGroup("Other", listOf(PatchCodeRow(1, "Max Money", null, false, false))),
                    ),
            )
        show()

        rule.onNodeWithText("PLAYER").assertExists()
        rule.onNodeWithText("OTHER").assertExists()
        press(Key.DirectionDown)
        rule.onNode(isFocused() and hasText("Max Money", substring = true)).assertExists()
    }

    @Test
    fun noCodesSaysWhichFileToAdd() {
        state = GameMenuState(booted = true, codes = codes())
        show()

        rule.onNodeWithText("No patch codes for this game.").assertExists()
        rule.onNodeWithText("Add Crash Bandicoot.cht to the cheats folder in your data folder.").assertExists()
    }

    @Test
    fun appliedAndErrorsShowInThePanel() {
        val rows = listOf(PatchCodeRow(0, "Max Money Now", null, true, false))
        state =
            GameMenuState(
                booted = true,
                codes = codes(PatchCodeGroup(null, rows)).copy(applied = "Applied: Max Money Now"),
            )
        show()
        rule.onNodeWithText("Applied: Max Money Now").assertExists()
    }

    @Test
    fun bLeavesTheCodePanelForItsRow() {
        state =
            GameMenuState(
                booted = true,
                codes = codes(PatchCodeGroup(null, listOf(PatchCodeRow(0, "Infinite HP", null, false, false)))),
            )
        show()

        press(Key.ButtonB)

        assertEquals(listOf("closeCodes"), calls)
        rule.onNodeWithText("Patch codes").assertIsFocused()
    }

    // The Thor's screen: the menu narrows beside a side panel and widens again as it closes, and the row it came from,
    // below the fold, must be scrolled back into view as it takes the focus.
    @Test
    fun aClosedPanelsRowIsShownAgainOnASmallScreen() {
        val rows = listOf(PatchCodeRow(0, "Infinite HP", null, false, false))
        state = GameMenuState(booted = true, analogMode = false, disc = 1, discCount = 2)
        show(width = 640, height = 360)
        for (step in 0 until 12) {
            if (rule.onNodeWithText("Patch codes").isFocusedNow()) break
            press(Key.DirectionDown)
        }
        rule.onNodeWithText("Patch codes").assertIsDisplayed()

        state = state.copy(codes = codes(PatchCodeGroup(null, rows)))
        rule.waitForIdle()
        press(Key.ButtonB)

        rule.onNodeWithText("Patch codes").assertIsFocused().assertIsDisplayed()
    }

    // As above, closing the disc panel by picking the disc already in.
    @Test
    fun pickingTheCurrentDiscShowsItsRowAgainOnASmallScreen() {
        state = GameMenuState(booted = true, analogMode = false, disc = 1, discCount = 2)
        show(width = 640, height = 360)
        for (step in 0 until 12) {
            if (rule.onNodeWithText("Change disc").isFocusedNow()) break
            press(Key.DirectionDown)
        }

        state = state.copy(discs = DiscChoice(ac3Discs, current = 1))
        rule.waitForIdle()
        rule.onNode(isFocused() and hasText("Disc 1", substring = true)).assertExists()
        press(Key.Enter)

        assertEquals(listOf("pickDisc 1"), calls)
        rule.onNodeWithText("Change disc").assertIsFocused().assertIsDisplayed()
    }

    private fun SemanticsNodeInteraction.isFocusedNow() =
        fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }

    @Test
    fun noSavesSaysSo() {
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Load, emptyList(), focus = null))
        show()

        rule.onNodeWithText("No saves yet for this game.").assertExists()
        rule.onNodeWithText("Slot 1").assertDoesNotExist()
    }

    @Test
    fun bStepsOutOneLayerAtATime() {
        state =
            GameMenuState(booted = true, slots = SlotPanel(SlotMode.Save, saved(3), focus = 3, replacing = 3))
        show()

        press(Key.ButtonB)
        assertEquals(listOf("cancelReplace"), calls)
        rule.onNode(isFocused() and hasText("Slot 3", substring = true)).assertExists()

        press(Key.ButtonB)
        assertEquals(listOf("cancelReplace", "closeSlots"), calls)
        rule.onNodeWithText("Save state").assertIsFocused()

        press(Key.ButtonB)
        assertEquals(listOf("cancelReplace", "closeSlots", "resume"), calls)
    }

    @Test
    fun aHeldBDoesNothingOnTheLayerItOpened() {
        show()
        rule.onRoot().performKeyInput { keyDown(Key.ButtonB) }
        state = state.copy(slots = SlotPanel(SlotMode.Save, saved(), focus = 1))
        rule.waitForIdle()

        rule.onRoot().performKeyInput { keyUp(Key.ButtonB) }
        rule.waitForIdle()

        assertEquals(emptyList<String>(), calls)
        // A press of its own still counts.
        press(Key.ButtonB)
        assertEquals(listOf("closeSlots"), calls)
    }

    @Test
    fun replacingAsksWithCancelFocused() {
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Save, saved(3), focus = 3, replacing = 3))
        show()

        rule.onNodeWithText("Replace the save in slot 3?").assertExists()
        rule.onNodeWithText("Cancel").assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        rule.onNode(isFocused() and hasText("Replace")).assertExists()
        press(Key.Enter)
        assertEquals(listOf("confirmReplace"), calls)
    }

    @Test
    fun resetAsksWithCancelFocused() {
        state = GameMenuState(booted = true, confirmingReset = true)
        show()

        rule.onNodeWithText("Reset the console? Progress since your last save is lost.").assertExists()
        rule.onNodeWithText("Cancel").assertIsFocused()

        press(Key.ButtonB)

        assertEquals(listOf("cancelReset"), calls)
        rule.onNodeWithText("Reset console").assertIsFocused()
    }

    @Test
    fun exitAsksWhileTheCardIsBeingWritten() {
        state = GameMenuState(booted = true, cardBusy = true, confirmingExit = true)
        show()

        rule
            .onNodeWithText("Exit the game? It's still saving to its memory card, and that save may be damaged.")
            .assertExists()
        rule.onNodeWithText("Cancel").assertIsFocused()
        press(Key.DirectionLeft)
        press(Key.Enter)
        assertEquals(listOf("confirmExit"), calls)

        press(Key.ButtonB)
        assertEquals(listOf("confirmExit", "cancelExit"), calls)
        rule.onNodeWithText("Exit game").assertIsFocused()
    }

    @Test
    fun exitAsksBeforeReplacingTheResumeState() {
        state = GameMenuState(booted = true, replacingResume = now.toEpochSecond() - 3_600)
        show()

        rule.onNodeWithText("Replace your saved place from Today · 13:05?").assertExists()
        rule.onNodeWithText("Keep it").assertIsFocused()
        press(Key.Enter)
        press(Key.DirectionLeft)
        press(Key.Enter)
        assertEquals(listOf("keepResume", "replaceResume"), calls)

        press(Key.ButtonB)
        assertEquals(listOf("keepResume", "replaceResume", "cancelReplaceResume"), calls)
        rule.onNodeWithText("Exit game").assertIsFocused()
    }

    @Test
    fun resetWarnsWhileTheCardIsBeingWritten() {
        state = GameMenuState(booted = true, cardBusy = true, confirmingReset = true)
        show()

        rule
            .onNodeWithText(
                "Reset the console? It's still saving to its memory card, and that save may be damaged. " +
                    "Progress since your last save is lost.",
            ).assertExists()
    }

    @Test
    fun anErrorShowsInThePanel() {
        val error = "Couldn't save to slot 2: Cannot save state while memory card is being saved."
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Save, saved(), focus = 2, error = error))
        show()

        rule.onNodeWithText(error).assertExists()
    }

    @Test
    fun focusStaysInTheSlotPanel() {
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Save, saved(), focus = 1))
        show()

        press(Key.DirectionLeft)
        press(Key.DirectionUp)

        rule.onNode(isFocused() and hasText("Slot 1", substring = true)).assertExists()
    }

    @Test
    fun aNarrowScreenStacksThePanels() {
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Save, saved(), focus = 1))
        show(width = 411, height = 891)

        val menu = rule.onNode(hasText("Save state") and hasClickAction()).getUnclippedBoundsInRoot()
        val slot = rule.onNode(isFocused() and hasText("Slot 1", substring = true))
        slot.assertIsDisplayed()
        val slotBox = slot.getUnclippedBoundsInRoot()
        assertTrue("the slot panel is below the menu", slotBox.top >= menu.bottom)
        assertTrue("the menu takes at most 40% of the height", menu.bottom <= (891 * 0.4f).dp)

        press(Key.ButtonB)

        rule.onNode(hasText("Save state") and hasClickAction()).assertIsFocused()
    }

    // The AYN Thor at 480 dpi: the menu narrows while the slot panel shows, so the pictures stay full size.
    @Test
    fun theThorsSlotPicturesAreFullSize() {
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Save, saved(1), focus = 1))
        show(width = 640, height = 360)

        val picture = rule.onAllNodesWithTag(SLOT_PICTURE, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        assertEquals(112f, (picture.right - picture.left).value, 0.5f)
        assertEquals(84f, (picture.bottom - picture.top).value, 0.5f)
    }

    @Test
    fun theHintsNameAAndB() {
        show()

        rule.onNodeWithContentDescription("A: Select").assertExists()
        rule.onNodeWithContentDescription("B: Back").assertExists()
    }

    @Test
    fun backResumes() {
        show()

        press(Key.Back)

        assertEquals(listOf("resume"), calls)
    }

    @Test
    fun modeResumesFromAnyLayer() {
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Save, saved(), focus = 1))
        show()

        press(Key.ButtonMode)

        assertEquals(listOf("resume"), calls)
    }

    @Test
    fun theKeyUpOfThePressThatOpenedItDoesNothing() {
        // The presses begin on what was there before the menu, and end on the menu.
        var open by mutableStateOf(false)
        rule.setContent {
            GooseStationTheme {
                if (open) {
                    GameMenu(state, actions, null, now)
                } else {
                    val before = remember { FocusRequester() }
                    LaunchedEffect(Unit) { before.requestFocus() }
                    Box(Modifier.size(10.dp).focusRequester(before).focusable())
                }
            }
        }
        rule.waitForIdle()
        val keys = listOf(Key.ButtonMode, Key.ButtonB, Key.Back, Key.ButtonStart)
        rule.onRoot().performKeyInput { keys.forEach { keyDown(it) } }
        open = true
        rule.waitForIdle()
        rule.onNodeWithText("Resume").assertIsFocused()

        rule.onRoot().performKeyInput { keys.forEach { keyUp(it) } }
        rule.waitForIdle()

        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithText("Resume").assertIsFocused()
        press(Key.ButtonMode)
        assertEquals(listOf("resume"), calls)
    }

    @Test
    fun backOpensTheMenuInTheGameThenResumesIt() {
        var menuOpen by mutableStateOf(false)
        val resuming =
            object : GameMenuActions by actions {
                override fun resume() {
                    calls += "resume"
                    menuOpen = false
                }
            }
        rule.setContent {
            GooseStationTheme {
                GameScreen(
                    menuOpen = menuOpen,
                    onOpenMenu = { menuOpen = true },
                    menu = state,
                    actions = resuming,
                )
            }
        }
        rule.waitForIdle()

        // Back reaches the game screen from the activity, as the system's Back does.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Resume").assertIsFocused()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(listOf("resume"), calls)
        rule.onNodeWithText("Resume").assertDoesNotExist()
    }
}
