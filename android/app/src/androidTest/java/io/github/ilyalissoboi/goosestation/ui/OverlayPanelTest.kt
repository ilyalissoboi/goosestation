// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
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
}
