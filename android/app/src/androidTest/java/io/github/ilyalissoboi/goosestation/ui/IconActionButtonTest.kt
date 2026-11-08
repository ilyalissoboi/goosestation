// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.ACTION_LABEL
import io.github.ilyalissoboi.goosestation.ui.components.ActionLabelStrip
import io.github.ilyalissoboi.goosestation.ui.components.IconActionButton
import io.github.ilyalissoboi.goosestation.ui.components.rememberActionLabelHost
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** An icon button: 48 dp, named by its label, which shows under it on focus and on a long press. */
@RunWith(AndroidJUnit4::class)
class IconActionButtonTest {
    @get:Rule val rule = createComposeRule()

    private val first = FocusRequester()
    private val last = FocusRequester()
    private var clicks = 0

    private fun show() {
        rule.setContent {
            GooseStationTheme {
                val labels = rememberActionLabelHost()
                Column(Modifier.width(320.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        IconActionButton(
                            GsIcons.sort,
                            "Sort: Title",
                            onClick = {},
                            labels = labels,
                            modifier = Modifier.focusRequester(first),
                        )
                        IconActionButton(
                            GsIcons.listBulleted,
                            "Show as a list",
                            onClick = { clicks++ },
                            labels = labels,
                        )
                        IconActionButton(
                            GsIcons.order,
                            "Order: Least recent first",
                            onClick = {},
                            labels = labels,
                            modifier = Modifier.focusRequester(last),
                        )
                    }
                    ActionLabelStrip(labels, Modifier.fillMaxWidth().testTag(STRIP))
                }
            }
        }
        rule.waitForIdle()
    }

    private fun focus(requester: FocusRequester) {
        rule.runOnIdle { requester.requestFocus() }
        rule.waitForIdle()
    }

    private fun label() = rule.onNodeWithTag(ACTION_LABEL, useUnmergedTree = true)

    @Test
    fun itIs48dpAndNamedByItsLabel() {
        show()

        rule
            .onNodeWithContentDescription("Sort: Title")
            .assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun noLabelShowsWithoutFocus() {
        show()

        label().assertDoesNotExist()
    }

    @Test
    fun aFocusedButtonShowsItsLabelUnderIt() {
        show()
        focus(first)

        label().assertTextEquals("Sort: Title")
        val button = rule.onNodeWithContentDescription("Sort: Title").getUnclippedBoundsInRoot()
        val shown = label().getUnclippedBoundsInRoot()
        assertTrue("label $shown, button $button", shown.top >= button.bottom)
        val centre = (shown.left + shown.right) / 2
        val buttonCentre = (button.left + button.right) / 2
        assertTrue("label $shown, button $button", abs((centre - buttonCentre).value) <= 1f)
    }

    @Test
    fun theLabelStaysInsideTheStrip() {
        show()
        focus(last)

        label().assertTextEquals("Order: Least recent first")
        val strip = rule.onNodeWithTag(STRIP).getUnclippedBoundsInRoot()
        val shown = label().getUnclippedBoundsInRoot()
        assertTrue("label $shown, strip $strip", shown.right <= strip.right + 0.5.dp && shown.left >= strip.left)
    }

    @Test
    fun aLongPressShowsTheLabelAndATapActs() {
        show()

        rule.onNodeWithContentDescription("Show as a list").performTouchInput { longClick() }
        rule.waitForIdle()

        label().assertTextEquals("Show as a list")
        assertEquals(0, clicks)

        rule.onNodeWithContentDescription("Show as a list").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun theStripKeepsItsHeight() {
        show()
        val before = rule.onNodeWithTag(STRIP).getUnclippedBoundsInRoot()

        focus(first)

        val after = rule.onNodeWithTag(STRIP).getUnclippedBoundsInRoot()
        assertEquals(before.bottom - before.top, after.bottom - after.top)
        assertTrue("strip $after", after.bottom - after.top > 0.dp)
    }

    private companion object {
        const val STRIP = "strip"
    }
}
