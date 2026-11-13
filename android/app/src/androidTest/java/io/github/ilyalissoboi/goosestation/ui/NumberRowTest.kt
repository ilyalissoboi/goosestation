// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.CHOICE_LIST
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.NumberRow
import io.github.ilyalissoboi.goosestation.ui.components.NumberScale
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The number row, section headings and disabled rows with a D-pad. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NumberRowTest {
    @get:Rule val rule = createComposeRule()

    private val scale = NumberScale.Range(0, 10, 1, presets = listOf(0, 5, 10)) { "$it goslings" }
    private val changes = mutableListOf<Int>()

    private fun show(
        start: Int = 5,
        enabled: Boolean = true,
        focusTheRow: Boolean = true,
    ) {
        rule.setContent {
            GooseStationTheme {
                var value by remember { mutableIntStateOf(start) }
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { first.requestFocus() }
                SettingsPage("Rows", onBack = {}) {
                    SwitchRow("Before", false, onToggle = {}, modifier = Modifier.focusRequester(first))
                    SettingsSection("Flock")
                    NumberRow(
                        "Goslings",
                        value,
                        scale,
                        onChange = {
                            changes += it
                            value = it
                        },
                        enabled = enabled,
                    )
                    SwitchRow("After", false, onToggle = {})
                }
            }
        }
        rule.waitForIdle()
        // From the switch above, past the heading.
        if (focusTheRow) press(Key.DirectionDown)
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertFocusedHas(text: String) {
        rule.onNode(isFocused()).assert(hasText(text, substring = true))
    }

    // The arrows of a row that hasn't had the focus yet, as a tap finds them when a page opens.
    private fun tapArrow(
        forward: Boolean,
        valueText: String,
    ) {
        val value = rule.onNodeWithText(valueText, useUnmergedTree = true).getBoundsInRoot()
        val density = rule.density.density
        val y = (value.top + value.bottom) / 2 * density
        // Each arrow is a 48 dp box beside the value: 20 dp past its edge is well inside it.
        val x = (if (forward) value.right + 20.dp else value.left - 20.dp).value * density
        rule.onRoot().performTouchInput { click(Offset(x, y.value)) }
        rule.waitForIdle()
    }

    @Test
    fun anUnfocusedRowsBackArrowSteps() {
        show(start = 5, focusTheRow = false)

        tapArrow(forward = false, "5 goslings")

        assertEquals(listOf(4), changes)
    }

    @Test
    fun anUnfocusedRowsForwardArrowStopsAtTheEnd() {
        show(start = 10, focusTheRow = false)

        tapArrow(forward = true, "10 goslings")

        assertEquals(emptyList<Int>(), changes)
    }

    @Test
    fun aSectionHeadingIsntAStop() {
        show()

        assertFocusedHas("Goslings")
    }

    @Test
    fun leftAndRightStep() {
        show()

        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)

        assertEquals(listOf(6, 5, 4), changes)
        assertFocusedHas("4 goslings")
    }

    @Test
    fun aHeldKeyRepeats() {
        show(start = 0)

        rule.onRoot().performKeyInput {
            keyDown(Key.DirectionRight)
            advanceEventTime(1_000)
            keyUp(Key.DirectionRight)
        }
        rule.waitForIdle()

        assertTrue("$changes", changes.size >= 3)
        assertEquals((1..changes.size).toList(), changes)
    }

    @Test
    fun itStopsAtItsEnds() {
        show(start = 10)

        press(Key.DirectionRight)

        assertEquals(emptyList<Int>(), changes)
        assertFocusedHas("10 goslings")
    }

    @Test
    fun aOpensTheCommonValuesWithTheCurrentOne() {
        show(start = 7)

        press(Key.ButtonA)

        assertFocusedHas("7 goslings")
        rule.onNode(isFocused()).assertIsSelected()
        for (preset in listOf("0 goslings", "5 goslings", "10 goslings")) {
            rule.onNode(hasText(preset).and(hasAnyAncestor(hasTestTag(CHOICE_LIST)))).assertExists()
        }
        press(Key.DirectionDown)
        press(Key.ButtonA)

        assertEquals(listOf(10), changes)
        assertFocusedHas("Goslings")
    }

    @Test
    fun bClosesTheListUnchanged() {
        show()

        press(Key.ButtonA)
        press(Key.DirectionDown)
        press(Key.ButtonB)

        assertEquals(emptyList<Int>(), changes)
        assertFocusedHas("Goslings")
        rule.onNode(hasTestTag(CHOICE_LIST)).assertDoesNotExist()
    }

    @Test
    fun aValueOffTheScaleShowsAsItself() {
        show(start = 40)

        assertFocusedHas("40 goslings")
        press(Key.DirectionLeft)
        assertEquals(listOf(10), changes)
    }

    @Test
    fun aDisabledNumberRowIsSkipped() {
        show(enabled = false)

        assertFocusedHas("After")
    }

    @Test
    fun disabledSwitchAndCyclerRowsAreSkipped() {
        rule.setContent {
            GooseStationTheme {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { first.requestFocus() }
                SettingsPage("Rows", onBack = {}) {
                    SwitchRow("Before", false, onToggle = {}, modifier = Modifier.focusRequester(first))
                    SwitchRow("Off switch", false, onToggle = {}, enabled = false, note = "Off in hardcore mode")
                    CyclerRow("Off cycler", listOf(Choice(1, "One")), 1, onSelect = {}, enabled = false)
                    SwitchRow("After", false, onToggle = {})
                }
            }
        }
        rule.waitForIdle()

        press(Key.DirectionDown)

        assertFocusedHas("After")
    }
}
