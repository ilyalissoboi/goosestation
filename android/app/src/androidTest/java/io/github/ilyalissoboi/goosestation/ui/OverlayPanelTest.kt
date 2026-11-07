// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A panel over the screen: the focus stays in it, B goes back, taps don't reach what's under it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OverlayPanelTest {
    @get:Rule val rule = createComposeRule()

    private var backs = 0
    private var underClicks = 0
    private var shown by mutableStateOf(true)

    private fun show() {
        rule.setContent {
            GooseStationTheme {
                Box(Modifier.fillMaxSize()) {
                    ActionButton("Under", onClick = { underClicks++ })
                    if (shown) {
                        OverlayPanel(onBack = { backs++ }) {
                            val first = remember { FocusRequester() }
                            LaunchedEffect(Unit) { first.requestFocus() }
                            ActionButton("One", onClick = {}, modifier = Modifier.focusRequester(first))
                            ActionButton("Two", onClick = {})
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedHas(text: String) =
        rule.onAllNodes(isFocused().and(hasText(text))).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theFocusCantLeave() {
        show()
        assertTrue(focusedHas("One"))

        press(Key.DirectionUp)
        assertTrue(focusedHas("One"))
        press(Key.DirectionDown)
        assertTrue(focusedHas("Two"))
        press(Key.DirectionDown)
        assertTrue(focusedHas("Two"))
        press(Key.DirectionLeft)
        assertTrue(focusedHas("Two"))
    }

    @Test
    fun bOnKeyUpCallsBack() {
        show()

        press(Key.ButtonB)

        assertEquals(1, backs)
    }

    @Test
    fun aBHeldFromBeforeDoesNothing() {
        shown = false
        show()
        rule.onRoot().performKeyInput { keyDown(Key.ButtonB) }
        shown = true
        rule.waitForIdle()

        rule.onRoot().performKeyInput { keyUp(Key.ButtonB) }
        rule.waitForIdle()

        assertEquals(0, backs)
    }

    @Test
    fun tapsDontReachWhatsUnder() {
        show()

        rule.onNodeWithText("Under").performTouchInput { click() }
        rule.waitForIdle()

        assertEquals(0, underClicks)
    }

    // A panel taller than the screen scrolls: its last button, focused, shows with the panel's padding under it, not
    // against its edge (the AYN Thor's favourite panel, Delete last).
    @Test
    fun theLastButtonShowsWithThePaddingUnderIt() {
        rule.setContent {
            GooseStationTheme {
                Box(Modifier.size(420.dp, 320.dp)) {
                    OverlayPanel(onBack = {}, modifier = Modifier.testTag("panel")) {
                        val first = remember { FocusRequester() }
                        LaunchedEffect(Unit) { first.requestFocus() }
                        ActionButton("Button 1", onClick = {}, modifier = Modifier.focusRequester(first))
                        (2..7).forEach { ActionButton("Button $it", onClick = {}) }
                        ActionButton("Last", onClick = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        // Down to the last, as the D-pad goes.
        repeat(7) { press(Key.DirectionDown) }
        assertTrue(focusedHas("Last"))

        val panel = rule.onNodeWithTag("panel").getUnclippedBoundsInRoot()
        val last = rule.onNodeWithText("Last").getUnclippedBoundsInRoot()
        val gap = panel.bottom - last.bottom
        assertTrue("$gap under the last button", gap >= 23.dp)
    }
}
