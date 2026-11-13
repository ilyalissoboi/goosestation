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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.Overlay
import io.github.ilyalissoboi.goosestation.ui.components.OverlayHost
import io.github.ilyalissoboi.goosestation.ui.components.OverlayHostBox
import io.github.ilyalissoboi.goosestation.ui.components.QuestionPanel
import io.github.ilyalissoboi.goosestation.ui.components.backKeys
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Overlays in an [OverlayHost]: while one shows, the screen under it is hidden from accessibility, so TalkBack reaches
 * only the overlay, which it announces as a pane.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OverlayHostTest {
    @get:Rule val rule = createComposeRule()

    private var first by mutableStateOf(true)
    private var second by mutableStateOf(false)
    private var list by mutableStateOf(false)
    private val closed = mutableListOf<String>()

    private val hiddenHere = SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)

    private fun show() {
        rule.setContent {
            GooseStationTheme {
                val host = remember { OverlayHost() }
                OverlayHostBox(host) {
                    Box(Modifier.fillMaxSize()) {
                        // As a screen does once its question closes: it focuses its own row again.
                        val under = remember { FocusRequester() }
                        LaunchedEffect(first) { if (!first) under.requestFocus() }
                        ActionButton("Under", onClick = {}, modifier = Modifier.focusRequester(under))
                        if (first) {
                            QuestionPanel("Delete?", "Delete", onConfirm = {}, onCancel = {
                                closed += "first"
                                first = false
                            })
                        }
                        // A list over the screen, as the library's sort list is: no focus trap of its own.
                        if (list) {
                            Overlay("Sort by") {
                                Box(
                                    Modifier.fillMaxSize().backKeys {
                                        closed += "list"
                                        list = false
                                    },
                                ) {
                                    ActionButton("Name", onClick = {})
                                }
                            }
                        }
                        if (second) {
                            QuestionPanel("Replace?", "Replace", onConfirm = {}, onCancel = {
                                closed += "second"
                                second = false
                            })
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    // Hidden from accessibility: the node itself, or one it's inside.
    private fun hidden(text: String) =
        rule
            .onAllNodes(hasText(text).and(hiddenHere.or(hasAnyAncestor(hiddenHere))), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    @Test
    fun theScreenUnderAnOverlayIsHiddenFromAccessibility() {
        show()

        assertTrue("the screen under it", hidden("Under"))
        assertFalse("the overlay itself", hidden("Delete"))
    }

    @Test
    fun theOverlayIsAPaneWithItsTitle() {
        show()

        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Delete?")).assertExists()
    }

    @Test
    fun closingItGivesTheScreenBack() {
        show()
        first = false
        rule.waitForIdle()

        assertFalse(hidden("Under"))
    }

    // From the final review: the host drops a closed panel a frame later, and its focus trap mustn't refuse the screen's
    // own request meanwhile, or nothing would be focused.
    @Test
    fun closingItLetsTheScreenTakeTheFocusBack() {
        show()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()

        assertEquals(listOf("first"), closed)
        rule.onNodeWithText("Under").assertIsFocused()
    }

    // Review Focus 2: drawn in the host, the overlay still takes the focus as it opens: its safe answer.
    @Test
    fun focusMovesIntoTheOverlayAsItOpens() {
        show()

        rule.onNodeWithText("Cancel").assertIsFocused()
    }

    // Review Focus 3: a question over a list (a resume prompt over the sort list): it's drawn on top, takes the focus,
    // and Back closes it alone. Two OverlayPanels at once never happen: each keeps the focus inside itself.
    @Test
    fun backClosesTheNewestOfTwo() {
        first = false
        list = true
        show()
        second = true
        rule.waitForIdle()

        rule.onNodeWithText("Cancel").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.Back) }
        rule.waitForIdle()

        assertEquals(listOf("second"), closed)
    }
}
