// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.GameMenuState
import io.github.ilyalissoboi.goosestation.app.SaveSlots
import io.github.ilyalissoboi.goosestation.app.SlotEntry
import io.github.ilyalissoboi.goosestation.app.SlotMode
import io.github.ilyalissoboi.goosestation.app.SlotPanel
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
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
                "Reset console",
                "Exit game",
            )
        for (label in order) {
            press(Key.DirectionDown)
            rule.onNode(isFocused() and hasText(label, substring = true)).assertExists()
        }
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

        assertEquals(
            listOf(
                "openSlots Save",
                "openSlots Load",
                "toggleFastForward",
                "toggleAnalogMode",
                "screenshot",
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
    fun emptySlotsInLoadAreSkipped() {
        val entries = listOf(SlotEntry(SaveSlots.RESUME, now.toEpochSecond())) + saved(4)
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Load, entries, focus = SaveSlots.RESUME))
        show()
        rule
            .onNode(isFocused() and hasText("Resume", substring = true) and hasText("Saved", substring = true))
            .assertExists()

        press(Key.DirectionDown)

        rule.onNode(isFocused() and hasText("Slot 4", substring = true)).assertExists()
    }

    @Test
    fun noSavesSaysSo() {
        state = GameMenuState(booted = true, slots = SlotPanel(SlotMode.Load, saved(), focus = null))
        show()

        rule.onNodeWithText("No saves yet for this game.").assertExists()
        rule.onNodeWithText("Slot 1").assertIsNotEnabled()
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
