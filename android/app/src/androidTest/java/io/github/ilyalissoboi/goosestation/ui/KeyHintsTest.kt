// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.KeyHint
import io.github.ilyalissoboi.goosestation.ui.components.KeyHints
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The button-hint footer: each glyph in a chip and its action, never focusable, wrapping on a narrow screen. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class KeyHintsTest {
    @get:Rule val rule = createComposeRule()

    private val selectAndBack = listOf(KeyHint(PadGlyphs.confirm, "Select"), KeyHint(PadGlyphs.back, "Back"))

    @Test
    fun itShowsEachGlyphAndAction() {
        rule.setContent { GooseStationTheme { KeyHints(selectAndBack) } }

        rule.onNodeWithText("A", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Select", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("B", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Back", useUnmergedTree = true).assertExists()
    }

    @Test
    fun nothingInItTakesFocus() {
        rule.setContent {
            GooseStationTheme {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { first.requestFocus() }
                Column {
                    ActionButton("Resume", onClick = {}, modifier = Modifier.focusRequester(first))
                    KeyHints(selectAndBack)
                }
            }
        }
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()

        rule.onNodeWithText("Resume").assertIsFocused()
        assertEquals(1, rule.onAllNodes(isFocusable()).fetchSemanticsNodes().size)
    }

    @Test
    fun itWrapsOnANarrowScreen() {
        rule.setContent {
            GooseStationTheme {
                KeyHints(
                    listOf(
                        KeyHint(PadGlyphs.confirm, "Select"),
                        KeyHint(PadGlyphs.back, "Back"),
                        KeyHint("X", "Something longer"),
                    ),
                    modifier = Modifier.width(200.dp),
                )
            }
        }

        val first = rule.onNodeWithText("Select", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val third = rule.onNodeWithText("Something longer", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the third hint wraps below the first", third.top >= first.bottom)
    }
}
