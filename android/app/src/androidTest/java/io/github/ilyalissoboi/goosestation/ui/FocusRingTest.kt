// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The focus ring, drawn outside its element, isn't covered by the element after it. */
@RunWith(AndroidJUnit4::class)
class FocusRingTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun theRingIsDrawnOverTheNextElement() {
        var focus = Color.Unspecified
        rule.setContent {
            GooseStationTheme {
                focus = GooseStationTheme.colors.focus
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { first.requestFocus() }
                // Two elements with no gap: the ring around the first lies over the top of the second.
                Column(Modifier.testTag("pair").padding(8.dp)) {
                    Box(Modifier.size(200.dp, 48.dp).focusRequester(first).focusRing(onClick = {}))
                    Box(Modifier.size(200.dp, 48.dp).focusRing(onClick = {}).background(Color.Red))
                }
            }
        }
        rule.waitForIdle()

        val image = rule.onNodeWithTag("pair").captureToImage().toPixelMap()
        // 3 dp below the first element: the middle of its ring.
        val x = with(rule.density) { (8.dp + 100.dp).roundToPx() }
        val y = with(rule.density) { (8.dp + 48.dp + 3.dp).roundToPx() }
        assertEquals(focus, image[x, y])
    }
}
