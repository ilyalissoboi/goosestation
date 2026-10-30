// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.ChoiceRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The settings rows with a D-pad, on a page of two choice rows and a switch, the first focused. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsRowsTest {
    @get:Rule val rule = createComposeRule()

    private val sizes = listOf(Choice("S", "Small"), Choice("M", "Medium"), Choice("L", "Large"))
    private val colours = listOf(Choice(1, "Red"), Choice(2, "Green", note = "The default"))
    private val sizesPicked = mutableListOf<String>()
    private val coloursPicked = mutableListOf<Int>()
    private val toggles = mutableListOf<Boolean>()
    private var backs = 0

    private fun show() {
        rule.setContent {
            GooseStationTheme {
                var size by remember { mutableStateOf("S") }
                var colour by remember { mutableStateOf(2) }
                var on by remember { mutableStateOf(false) }
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { first.requestFocus() }
                SettingsPage("Rows", onBack = { backs++ }) {
                    ChoiceRow(
                        "Size",
                        sizes,
                        size,
                        onSelect = {
                            sizesPicked += it
                            size = it
                        },
                        modifier = Modifier.focusRequester(first),
                    )
                    ChoiceRow(
                        "Colour",
                        colours,
                        colour,
                        onSelect = {
                            coloursPicked += it
                            colour = it
                        },
                        note = "Of the goose",
                    )
                    SwitchRow(
                        "Honk",
                        on,
                        onToggle = {
                            toggles += it
                            on = it
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertFocusedHas(vararg texts: String) {
        texts.forEach { rule.onNode(isFocused()).assert(hasText(it)) }
    }

    @Test
    fun enterOpensTheRowWithTheSelectedChoiceFocused() {
        show()
        assertFocusedHas("Size", "Small")
        rule.onNodeWithText("Large").assertDoesNotExist()

        press(Key.Enter)

        rule.onNodeWithText("Large").assertExists()
        assertFocusedHas("Small", "✓")
    }

    @Test
    fun downThenEnterPicksTheNextChoiceAndFocusesTheRow() {
        show()
        press(Key.Enter)

        press(Key.DirectionDown)
        assertFocusedHas("Medium")
        press(Key.Enter)

        assertEquals(listOf("M"), sizesPicked)
        rule.onNodeWithText("Large").assertDoesNotExist()
        assertFocusedHas("Size", "Medium")
    }

    @Test
    fun theSelectedChoiceAgainChangesNothing() {
        show()
        press(Key.Enter)

        press(Key.Enter)

        assertEquals(emptyList<String>(), sizesPicked)
        rule.onNodeWithText("Large").assertDoesNotExist()
        assertFocusedHas("Size", "Small")
    }

    @Test
    fun backClosesTheRowWithoutAChange() {
        show()
        press(Key.Enter)
        press(Key.DirectionDown)

        press(Key.Back)

        assertEquals(emptyList<String>(), sizesPicked)
        assertEquals(0, backs)
        rule.onNodeWithText("Large").assertDoesNotExist()
        assertFocusedHas("Size", "Small")

        press(Key.Back)

        assertEquals(1, backs)
    }

    @Test
    fun buttonBClosesTheRowToo() {
        show()
        press(Key.Enter)

        press(Key.ButtonB)

        assertEquals(0, backs)
        rule.onNodeWithText("Large").assertDoesNotExist()
        assertFocusedHas("Size", "Small")
    }

    @Test
    fun openingTheSecondRowClosesTheFirst() {
        show()
        press(Key.Enter)
        rule.onNodeWithText("Large").assertExists()

        // Through Small, Medium and Large to the Colour row.
        repeat(3) { press(Key.DirectionDown) }
        assertFocusedHas("Colour")
        press(Key.Enter)

        rule.onNodeWithText("Large").assertDoesNotExist()
        rule.onNodeWithText("Red").assertExists()
        assertFocusedHas("Green", "✓", "The default")
    }

    @Test
    fun theSwitchToggles() {
        show()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertFocusedHas("Honk", "Off")

        press(Key.Enter)

        assertEquals(listOf(true), toggles)
        assertFocusedHas("Honk", "On")
    }

    @Test
    fun theRowsNoteShows() {
        show()

        rule.onNodeWithText("Of the goose").assertExists()
    }
}
