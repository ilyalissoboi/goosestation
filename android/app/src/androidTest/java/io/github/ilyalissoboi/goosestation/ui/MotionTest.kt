// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The design system's motion at its own speed: focus and toggles over 120 ms. The suite runs with the system's
 * animations off, which a test's clock doesn't follow, so this one sets the speed itself.
 */
@RunWith(AndroidJUnit4::class)
class MotionTest {
    @get:Rule val rule = createComposeRule(effectContext = AtSpeed(1f))

    @Test
    fun theFocusRingFadesIn() {
        var focus = Color.Unspecified
        rule.mainClock.autoAdvance = false
        rule.setContent {
            GooseStationTheme {
                focus = GooseStationTheme.colors.focus
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { first.requestFocus() }
                Box(Modifier.testTag("ring").background(Color.Black).padding(8.dp)) {
                    Box(Modifier.size(200.dp, 48.dp).focusRequester(first).focusRing(onClick = {}))
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(60)

        val half = ringPixel()
        assertNotEquals("partly drawn at 60 ms", focus, half)
        assertNotEquals("partly drawn at 60 ms", Color.Black, half)

        rule.mainClock.advanceTimeBy(100)
        assertEquals("fully drawn by 120 ms", focus, ringPixel())
    }

    @Test
    fun aToggleSlidesOver120ms() {
        showSwitch(rule)
        val start = thumbLeft(rule)

        rule.onNodeWithText("Honk").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(60)
        val midway = thumbLeft(rule)
        rule.mainClock.advanceTimeBy(80)
        val end = thumbLeft(rule)

        val travel = with(rule.density) { 20.dp.toPx() }
        assertTrue("midway at 60 ms: $start < $midway < ${start + travel}", midway > start && midway < start + travel)
        assertTrue("at the end by 140 ms: $end", abs(end - (start + travel)) < 1f)
    }

    // The middle of the ring, 3 dp outside the element's top edge.
    private fun ringPixel(): Color {
        val image = rule.onNodeWithTag("ring").captureToImage().toPixelMap()
        val x = with(rule.density) { (8.dp + 100.dp).roundToPx() }
        val y = with(rule.density) { (8.dp - 3.dp).roundToPx() }
        return image[x, y]
    }
}

/** With the system's animations off: a toggle is at its end on the next frame. */
@RunWith(AndroidJUnit4::class)
class NoMotionTest {
    @get:Rule val rule = createComposeRule(effectContext = AtSpeed(0f))

    @Test
    fun withAnimationsOffAToggleJumps() {
        showSwitch(rule)
        val start = thumbLeft(rule)

        rule.onNodeWithText("Honk").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()

        val travel = with(rule.density) { 20.dp.toPx() }
        assertTrue("at the end at once: ${thumbLeft(rule)}", abs(thumbLeft(rule) - (start + travel)) < 1f)
    }
}

/** Animations at [scaleFactor] of their duration: 1 as written, 0 as with the system's animations off. */
class AtSpeed(
    override val scaleFactor: Float,
) : MotionDurationScale

private fun showSwitch(rule: ComposeContentTestRule) {
    rule.mainClock.autoAdvance = false
    rule.setContent {
        GooseStationTheme {
            var on by remember { mutableStateOf(false) }
            SwitchRow("Honk", on, onToggle = { on = it })
        }
    }
    rule.mainClock.advanceTimeByFrame()
}

private fun thumbLeft(rule: ComposeContentTestRule): Float =
    rule
        .onNodeWithTag("switch-thumb", useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot.left
