// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.settings.ConsoleSettings
import io.github.ilyalissoboi.goosestation.settings.NextStart
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.GameContext
import io.github.ilyalissoboi.goosestation.ui.components.LocalGameContext
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The shared rows on a game's page, with a D-pad: the Console page with a game's values. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GameRowsTest {
    @get:Rule val rule = createComposeRule()

    private var shown: SettingValues? = null

    private fun show(
        start: SettingValues,
        context: GameContext? = GameContext("Pad Test", running = false, hardcoreActive = false),
    ) {
        rule.setContent {
            GooseStationTheme {
                var values by remember { mutableStateOf(start) }
                shown = values
                CompositionLocalProvider(LocalGameContext provides context) {
                    ConsoleScreen(values, SampleNames.ALL, onChange = { values = it }, onBack = {})
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun downTo(text: String) {
        repeat(30) {
            if (focusedHas(text)) return
            press(Key.DirectionDown)
        }
        throw AssertionError("Down never reached $text")
    }

    private fun focusedHas(text: String) =
        rule.onAllNodes(isFocused() and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun shownCount(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun aGameSwitchIsThreeWay() {
        show(gamePageValues(ConsoleSettings.all))
        downTo("8 MB RAM")
        assertTrue(focusedHas("Use global (Off)"))
        assertEquals(0, shownCount(SET_FOR_GAME))

        press(Key.DirectionRight)

        assertTrue(shown!!.isSet(ConsoleSettings.RAM_8MB))
        assertTrue(shown!![ConsoleSettings.RAM_8MB])
        assertTrue(focusedHas(SET_FOR_GAME))

        press(Key.ButtonA)
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        press(Key.ButtonA)

        assertFalse(shown!!.isSet(ConsoleSettings.RAM_8MB))
        assertEquals(0, shownCount(SET_FOR_GAME))
    }

    @Test
    fun aGameChoiceStartsWithUseGlobal() {
        show(gamePageValues(ConsoleSettings.all, global = mapOf("Console/Region" to "NTSC-J")))
        assertTrue(focusedHas("Use global (NTSC-J (Japan))"))

        press(Key.DirectionRight)

        assertTrue(shown!!.isSet(ConsoleSettings.REGION))
        press(Key.DirectionLeft)
        assertFalse(shown!!.isSet(ConsoleSettings.REGION))
        assertEquals("NTSC-J", shown!![ConsoleSettings.REGION])
    }

    @Test
    fun aGameNumberStepsFromTheGlobalValue() {
        show(gamePageValues(ConsoleSettings.all, global = mapOf("CDROM/ReadaheadSectors" to "16")))
        downTo("Readahead")
        assertTrue(focusedHas("Use global (16 sectors)"))

        press(Key.DirectionLeft)
        assertFalse("Left from Use global does nothing", shown!!.isSet(ConsoleSettings.READAHEAD_SECTORS))

        press(Key.DirectionRight)
        assertTrue(shown!!.isSet(ConsoleSettings.READAHEAD_SECTORS))
        assertEquals(16, shown!![ConsoleSettings.READAHEAD_SECTORS])

        repeat(16) { press(Key.DirectionLeft) }
        assertEquals(0, shown!![ConsoleSettings.READAHEAD_SECTORS])
        assertTrue(shown!!.isSet(ConsoleSettings.READAHEAD_SECTORS))

        press(Key.DirectionLeft)
        assertFalse("Left from the lowest goes back to Use global", shown!!.isSet(ConsoleSettings.READAHEAD_SECTORS))
    }

    @Test
    fun aGameNumbersListStartsWithUseGlobal() {
        show(gamePageValues(ConsoleSettings.all, game = mapOf("CDROM/ReadaheadSectors" to "4")))
        downTo("Readahead")

        press(Key.ButtonA)
        repeat(10) { press(Key.DirectionUp) }
        press(Key.ButtonA)

        assertFalse(shown!!.isSet(ConsoleSettings.READAHEAD_SECTORS))
        assertEquals(8, shown!![ConsoleSettings.READAHEAD_SECTORS])
    }

    @Test
    fun aNextStartRowSaysSoInARunningGame() {
        show(gamePageValues(ConsoleSettings.all), GameContext("Pad Test", running = true, hardcoreActive = false))
        assertTrue(focusedHas(NextStart.NOTE))
    }

    @Test
    fun aLibraryGamesRowSaysNothingOfTheNextStart() {
        show(gamePageValues(ConsoleSettings.all))
        assertEquals(0, shownCount(NextStart.NOTE))
    }

    @Test
    fun aGamePageShowsTheGamesTitle() {
        show(gamePageValues(ConsoleSettings.all))
        rule.onNodeWithText("Pad Test", ignoreCase = true).assertExists()
    }

    @Test
    fun theGlobalRowsAreUnchanged() {
        show(pageValues(ConsoleSettings.all), context = null)
        assertEquals(0, shownCount("Use global"))
        assertEquals(0, shownCount(SET_FOR_GAME))
    }
}
