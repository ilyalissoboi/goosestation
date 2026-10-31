// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.CHOICE_LIST
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The settings rows with a D-pad, on a page of two cycler rows and a switch, the first focused. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsRowsTest {
    @get:Rule val rule = createComposeRule()

    private val sizes = mutableStateListOf(Choice("S", "Small"), Choice("M", "Medium"), Choice("L", "Large"))
    private val colours = listOf(Choice(1, "Red"), Choice(2, "Green", note = "The default"))
    private val sizesPicked = mutableListOf<String>()
    private val coloursPicked = mutableListOf<Int>()
    private val toggles = mutableListOf<Boolean>()
    private var backs = 0

    private fun show(size: String = "S") {
        rule.setContent {
            GooseStationTheme {
                var current by remember { mutableStateOf(size) }
                var colour by remember { mutableStateOf(2) }
                var on by remember { mutableStateOf(false) }
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { first.requestFocus() }
                SettingsPage("Rows", onBack = { backs++ }) {
                    CyclerRow(
                        "Size",
                        sizes,
                        current,
                        onSelect = {
                            sizesPicked += it
                            current = it
                        },
                        modifier = Modifier.focusRequester(first),
                    )
                    CyclerRow(
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
    fun theRowShowsOnlyTheSelectedChoice() {
        show()

        assertFocusedHas("Size", "Small")
        rule.onNodeWithText("Medium").assertDoesNotExist()
        rule.onNodeWithText("Large").assertDoesNotExist()
    }

    @Test
    fun rightStepsToTheNextChoiceAndKeepsTheFocus() {
        show()

        press(Key.DirectionRight)

        assertEquals(listOf("M"), sizesPicked)
        assertFocusedHas("Size", "Medium")
    }

    @Test
    fun leftStepsBack() {
        show(size = "L")

        press(Key.DirectionLeft)

        assertEquals(listOf("M"), sizesPicked)
        assertFocusedHas("Size", "Medium")
    }

    @Test
    fun theArrowsStopAtTheEnds() {
        show()
        press(Key.DirectionLeft)

        assertEquals(emptyList<String>(), sizesPicked)
        assertFocusedHas("Size", "Small")

        press(Key.DirectionRight)
        press(Key.DirectionRight)
        press(Key.DirectionRight)

        assertEquals(listOf("M", "L"), sizesPicked)
        assertFocusedHas("Size", "Large")
    }

    private fun list() = rule.onNodeWithTag(CHOICE_LIST, useUnmergedTree = true)

    private fun choice(text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(CHOICE_LIST)))

    private fun assertFocusedChoice(text: String) {
        rule.onNode(isFocused()).assert(hasText(text)).assert(hasAnyAncestor(hasTestTag(CHOICE_LIST)))
    }

    private fun assertClosedOnTheRow(vararg texts: String) {
        list().assertDoesNotExist()
        assertFocusedHas(*texts)
    }

    @Test
    fun aOpensTheListOnTheCurrentChoice() {
        show()

        press(Key.ButtonA)

        choice("Small").assertIsSelected()
        choice("Medium").assertIsNotSelected()
        choice("Large").assertExists()
        assertFocusedChoice("Small")
        rule.onNode(hasText("Size") and hasStateDescription("Expanded")).assertExists()
        assertEquals(emptyList<String>(), sizesPicked)
    }

    @Test
    fun enterOpensItToo() {
        show()

        press(Key.Enter)

        assertFocusedChoice("Small")
        assertEquals(emptyList<String>(), sizesPicked)
    }

    @Test
    fun upAndDownMoveAndStopAtTheEnds() {
        show()
        press(Key.ButtonA)

        repeat(5) { press(Key.DirectionDown) }
        assertFocusedChoice("Large")
        repeat(5) { press(Key.DirectionUp) }
        assertFocusedChoice("Small")

        assertEquals(emptyList<String>(), sizesPicked)
    }

    @Test
    fun aPicksAndCloses() {
        show()
        press(Key.ButtonA)
        press(Key.DirectionDown)

        press(Key.ButtonA)

        assertEquals(listOf("M"), sizesPicked)
        assertClosedOnTheRow("Size", "Medium")
        rule.onNode(hasText("Size") and hasStateDescription("Collapsed")).assertExists()
    }

    @Test
    fun aOnTheCurrentChoiceChangesNothing() {
        show()
        press(Key.ButtonA)

        press(Key.ButtonA)

        assertEquals(emptyList<String>(), sizesPicked)
        assertClosedOnTheRow("Size", "Small")
    }

    @Test
    fun bClosesWithoutAChange() {
        show()
        press(Key.ButtonA)
        press(Key.DirectionDown)

        press(Key.ButtonB)

        assertEquals(emptyList<String>(), sizesPicked)
        assertClosedOnTheRow("Size", "Small")
        assertEquals(0, backs)
    }

    @Test
    fun systemBackClosesTheListNotThePage() {
        show()
        press(Key.ButtonA)
        press(Key.DirectionDown)

        press(Key.Back)

        assertEquals(emptyList<String>(), sizesPicked)
        assertClosedOnTheRow("Size", "Small")
        assertEquals(0, backs)
    }

    @Test
    fun leftAndRightDoNothingWhileOpen() {
        show()
        press(Key.ButtonA)

        press(Key.DirectionRight)
        press(Key.DirectionLeft)

        assertFocusedChoice("Small")
        assertEquals(emptyList<String>(), sizesPicked)
    }

    @Test
    fun theFocusCantLeaveTheList() {
        show()
        press(Key.ButtonA)
        repeat(3) { press(Key.DirectionDown) }

        press(Key.DirectionDown)

        assertFocusedChoice("Large")
    }

    @Test
    fun eachChoiceShowsItsNote() {
        show()
        press(Key.DirectionDown)

        press(Key.ButtonA)

        assertFocusedChoice("Green")
        choice("Green").assertIsSelected()
        rule.onNode(isFocused()).assert(hasText("The default"))
    }

    @Test
    fun theAThatOpensDoesntPick() {
        show()

        rule.onRoot().performKeyInput {
            keyDown(Key.ButtonA)
            advanceEventTime(100)
            keyUp(Key.ButtonA)
        }
        rule.waitForIdle()

        assertFocusedChoice("Small")
        assertEquals(emptyList<String>(), sizesPicked)
    }

    @Test
    fun aValueNotAmongTheChoices() {
        show(size = "XL")

        press(Key.ButtonA)

        choice("Small").assertIsNotSelected()
        choice("Medium").assertIsNotSelected()
        choice("Large").assertIsNotSelected()
        assertFocusedChoice("Small")
    }

    @Test
    fun choicesChangingWhileOpen() {
        show(size = "S")
        press(Key.ButtonA)
        press(Key.DirectionDown)
        assertFocusedChoice("Medium")

        rule.runOnIdle { sizes.removeAt(1) }
        rule.waitForIdle()
        assertFocusedChoice("Small")

        rule.runOnIdle { sizes.clear() }
        rule.waitForIdle()
        list().assertDoesNotExist()
        assertFocusedHas("Size")
    }

    @Test
    fun aTapOnTheLabelLineCloses() {
        show()
        press(Key.ButtonA)
        list().assertExists()

        rule.onNodeWithText("Size", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        list().assertDoesNotExist()
        assertEquals(emptyList<String>(), sizesPicked)
    }

    @Test
    fun aTapElsewhereClosesAndActs() {
        show()
        press(Key.ButtonA)
        list().assertExists()

        rule.onNodeWithText("Honk").performClick()
        rule.waitForIdle()

        list().assertDoesNotExist()
        assertEquals(listOf(true), toggles)
    }

    @Test
    fun openingAnotherClosesTheFirst() {
        show()
        rule.onNodeWithText("Size", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        choice("Large").assertExists()

        rule.onNodeWithText("Colour", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        choice("Large").assertDoesNotExist()
        choice("Red").assertExists()
    }

    @Test
    fun downMovesToTheNextRowWithoutAChange() {
        show()

        press(Key.DirectionDown)

        assertEquals(emptyList<String>(), sizesPicked)
        assertFocusedHas("Colour", "Green")
    }

    @Test
    fun theSelectedChoicesNoteShowsWithIt() {
        show()
        press(Key.DirectionDown)
        assertFocusedHas("Colour", "Green", "The default")

        press(Key.DirectionLeft)

        assertEquals(listOf(1), coloursPicked)
        assertFocusedHas("Colour", "Red")
        rule.onNodeWithText("The default").assertDoesNotExist()
    }

    @Test
    fun theRowsNoteShows() {
        show()

        rule.onNodeWithText("Of the goose").assertExists()
    }

    @Test
    fun backAndButtonBGoBack() {
        show()

        press(Key.Back)
        press(Key.ButtonB)

        assertEquals(2, backs)
        assertEquals(emptyList<String>(), sizesPicked)
    }

    @Test
    fun theSwitchToggles() {
        show()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertFocusedHas("Honk")
        rule.onNode(isFocused()).assertIsOff()

        press(Key.Enter)

        assertEquals(listOf(true), toggles)
        rule.onNode(isFocused()).assertIsOn()
    }

    @Test
    fun theSwitchShowsNoWord() {
        show()

        rule.onNodeWithText("Off").assertDoesNotExist()
        rule.onNodeWithText("On").assertDoesNotExist()
    }
}
