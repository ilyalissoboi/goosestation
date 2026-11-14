// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.settings.ConsoleSettings
import io.github.ilyalissoboi.goosestation.settings.NextStart
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.GameContext
import io.github.ilyalissoboi.goosestation.ui.components.LocalGameContext
import io.github.ilyalissoboi.goosestation.ui.components.NumberScale
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
class GameRowsTest : ControllerKeys {
    @get:Rule override val rule = createComposeRule()

    private var shown: SettingValues? = null

    // Two grouped rows over next-start settings: the region as a choice, the 8 MB RAM switch as a 0 or 1 number.
    @Composable
    private fun GroupedRows(
        values: SettingValues,
        onChange: (SettingValues) -> Unit,
        enabled: Boolean = true,
    ) = Column {
        GameGroupChoices(
            values,
            onChange,
            REGION_GROUP,
            listOf(ConsoleSettings.REGION),
            listOf(Choice("Auto", "Auto-Detect"), Choice("NTSC-J", "NTSC-J (Japan)")),
            value = { it[ConsoleSettings.REGION] },
            write = { v, region -> v.with(ConsoleSettings.REGION, region) },
            enabled = enabled,
        )
        GameGroupNumber(
            values,
            onChange,
            RAM_GROUP,
            listOf(ConsoleSettings.RAM_8MB),
            NumberScale.Steps(listOf(0, 1), presets = listOf(0, 1)) { "$it" },
            value = { if (it[ConsoleSettings.RAM_8MB]) 1 else 0 },
            write = { v, on -> v.with(ConsoleSettings.RAM_8MB, on == 1) },
            enabled = enabled,
        )
    }

    private fun showRows(
        start: SettingValues,
        context: GameContext?,
        content: @Composable (SettingValues, (SettingValues) -> Unit) -> Unit,
    ) {
        rule.setContent {
            GooseStationTheme {
                var values by remember { mutableStateOf(start) }
                shown = values
                CompositionLocalProvider(LocalGameContext provides context) {
                    content(values) { values = it }
                }
            }
        }
        rule.waitForIdle()
    }

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

    private fun shownCount(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    // A grouped row over a setting a running game takes only at its next start says so, as a single-setting row does.
    @Test
    fun aGroupedRowOfANextStartSettingSaysSoInARunningGame() {
        showRows(gamePageValues(ConsoleSettings.all), RUNNING) { values, onChange -> GroupedRows(values, onChange) }

        assertEquals(2, shownCount(NextStart.NOTE))
    }

    @Test
    fun aDisabledGroupedRowCantBeChanged() {
        showRows(gamePageValues(ConsoleSettings.all), RUNNING) { values, onChange ->
            GroupedRows(values, onChange, enabled = false)
        }

        for (label in listOf(REGION_GROUP, RAM_GROUP)) {
            val row = rule.onNode(hasText(label, substring = true))
            row.assertIsNotEnabled()
            row.performClick()
        }
        rule.waitForIdle()
        assertFalse(shown!!.isSet(ConsoleSettings.REGION))
        assertFalse(shown!!.isSet(ConsoleSettings.RAM_8MB))
    }

    // Review Focus 2: on the global page a switch stays on or off, with no Use global.
    @Test
    fun aSwitchOnTheGlobalPageIsOnOrOff() {
        showRows(pageValues(ConsoleSettings.all), context = null) { values, onChange ->
            GameSwitch(values, onChange, "8 MB RAM", ConsoleSettings.RAM_8MB)
        }
        assertEquals(0, shownCount("Use global"))

        rule.onNodeWithText("8 MB RAM", substring = true).performClick()
        rule.waitForIdle()

        assertTrue(shown!![ConsoleSettings.RAM_8MB])
    }

    // Review Focus 3: a game's own value and the global one, neither named by the core, are both offered.
    @Test
    fun aGameNamesRowOffersTheGamesAndTheGlobalUnnamedValues() {
        // The Console page's first row is its region, a GameNames row; its list opens over the page.
        show(
            gamePageValues(
                ConsoleSettings.all,
                global = mapOf("Console/Region" to "Other"),
                game = mapOf("Console/Region" to "Odd"),
            ),
        )
        assertTrue(focusedHas("Odd"))

        press(Key.ButtonA)

        assertTrue("the game's own value, offered", shownCount("Odd") >= 2)
        assertTrue("Use global (Other) and Other itself", shownCount("Other") >= 2)
    }

    @Test
    fun aGameSwitchIsThreeWay() {
        show(gamePageValues(ConsoleSettings.all))
        walkTo("8 MB RAM")
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
        walkTo("Readahead")
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
        walkTo("Readahead")

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

    private companion object {
        val RUNNING = GameContext("Pad Test", running = true, hardcoreActive = false)
        const val REGION_GROUP = "Region group"
        const val RAM_GROUP = "RAM group"
    }
}
