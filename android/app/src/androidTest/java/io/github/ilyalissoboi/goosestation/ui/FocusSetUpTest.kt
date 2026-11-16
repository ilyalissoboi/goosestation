// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.FocusHolderLine
import io.github.ilyalissoboi.goosestation.ui.components.rememberFocusRequesters
import io.github.ilyalissoboi.goosestation.ui.components.rememberInitialFocus
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The focus set-up helpers: the page's first focus, a requester per row, and the line that holds the focus. */
@RunWith(AndroidJUnit4::class)
class FocusSetUpTest {
    @get:Rule val rule = createComposeRule()

    // A page with nothing to press: its line takes the focus as it opens, with the ring, and isn't a button.
    @Test
    fun aHolderLineTakesTheFocusWithTheRing() {
        var focus = Color.Unspecified
        rule.setContent {
            GooseStationTheme {
                focus = GooseStationTheme.colors.focus
                Column(Modifier.testTag("page").padding(16.dp)) {
                    FocusHolderLine("Connect a controller to set it up.", requester = rememberInitialFocus())
                }
            }
        }
        rule.waitForIdle()

        val line = rule.onNodeWithText("Connect a controller to set it up.")
        line.assertIsFocused()
        line.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
        // 3 dp above the line: the middle of its ring.
        val bounds = line.getUnclippedBoundsInRoot()
        val page = rule.onNodeWithTag("page").getUnclippedBoundsInRoot()
        val image = rule.onNodeWithTag("page").captureToImage().toPixelMap()
        val x = with(rule.density) { (bounds.left - page.left + 24.dp).roundToPx() }
        val y = with(rule.density) { (bounds.top - page.top - 3.dp).roundToPx() }
        assertEquals(focus, image[x, y])
    }

    // Below a row that takes the focus, as the port's line below its type row: only a line.
    @Test
    fun aHolderLineNotEnabledIsNoStop() {
        rule.setContent {
            GooseStationTheme {
                Column {
                    ActionButton("Type", onClick = {}, modifier = Modifier.padding(4.dp))
                    FocusHolderLine("No controller is on Port 1.", enabled = false)
                }
            }
        }
        rule.waitForIdle()

        rule
            .onNodeWithText("No controller is on Port 1.")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
    }

    @Test
    fun aKeyKeepsItsRequester() {
        var first: FocusRequester? = null
        var again: FocusRequester? = null
        var other: FocusRequester? = null
        rule.setContent {
            val rows = rememberFocusRequesters<Int>()
            first = rows[1]
            again = rows[1]
            other = rows[2]
        }
        rule.waitForIdle()

        assertSame(first, again)
        assertNotSame(first, other)
    }
}
