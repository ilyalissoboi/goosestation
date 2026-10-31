// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Scrolling with a controller: the focused row stays in the middle once the page scrolls, whole rows around it, and
 * the page stops at its ends, with the title at the top and room for the focus outline at the bottom.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ScrollingTest {
    @get:Rule val rule = createComposeRule()

    private fun show() {
        rule.setContent {
            GooseStationTheme {
                Box(Modifier.requiredSize(640.dp, 360.dp)) {
                    SettingsPage("A long page", onBack = {}) {
                        val first = remember { FocusRequester() }
                        LaunchedEffect(Unit) { first.requestFocus() }
                        for (row in 1..ROWS) {
                            SwitchRow(
                                "Row $row",
                                false,
                                onToggle = {},
                                modifier = if (row == 1) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(
        key: Key,
        times: Int = 1,
    ) {
        repeat(times) {
            rule.onRoot().performKeyInput { pressKey(key) }
            rule.waitForIdle()
        }
    }

    private fun viewport() = rule.onNode(hasScrollAction()).getUnclippedBoundsInRoot()

    private fun focused() = rule.onNode(isFocused()).getUnclippedBoundsInRoot()

    @Test
    fun theFocusedRowStaysInTheMiddle() {
        show()

        press(Key.DirectionDown, 10)

        val viewport = viewport()
        val row = focused()
        val middle = (viewport.top + viewport.bottom) / 2
        val rowMiddle = (row.top + row.bottom) / 2
        assertTrue("the row's middle $rowMiddle is near the viewport's $middle", abs((rowMiddle - middle).value) < 8f)
    }

    @Test
    fun goingBackUpShowsTheTitle() {
        show()
        press(Key.DirectionDown, 15)

        press(Key.DirectionUp, 15)

        rule.onNode(isFocused() and hasText("Row 1")).assertExists()
        val title = rule.onNodeWithText("A long page").getUnclippedBoundsInRoot()
        assertTrue("the title is in view", title.top >= viewport().top - 0.5.dp)
    }

    @Test
    fun theLastRowHasRoomForItsOutline() {
        show()

        press(Key.DirectionDown, ROWS)

        rule.onNode(isFocused() and hasText("Row $ROWS")).assertExists()
        assertTrue("4 dp below the last row", focused().bottom + 4.dp <= viewport().bottom + 0.5.dp)
    }

    private companion object {
        const val ROWS = 24
    }
}
