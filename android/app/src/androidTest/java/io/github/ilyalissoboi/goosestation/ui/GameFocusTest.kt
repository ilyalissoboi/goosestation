// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The library's focus keeps a requester for each game on screen, and none for one scrolled away. */
@RunWith(AndroidJUnit4::class)
class GameFocusTest {
    @get:Rule val rule = createComposeRule()

    private val focus = GameFocus()
    private val list = LazyListState()
    private val paths = List(200) { "/games/$it.cue" }

    private fun show() {
        rule.setContent {
            LazyColumn(state = list, modifier = Modifier.width(300.dp).height(300.dp).then(focus.screenModifier)) {
                items(paths, key = { it }) { path ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .then(focus.modifier(path))
                            .focusable(),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun scrollTo(index: Int) {
        rule.runOnIdle { runBlocking { list.scrollToItem(index) } }
        rule.waitForIdle()
    }

    @Test
    fun aGameOnScreenTakesTheFocusWhenAsked() {
        show()

        assertTrue(rule.runOnIdle { focus.request(paths[2]) })
        rule.runOnIdle { assertEquals(paths[2], focus.focused) }
    }

    @Test
    fun aGameScrolledAwayIsntAskedAndIsntHeld() {
        show()
        val shownAtFirst = rule.runOnIdle { focus.held }

        scrollTo(150)

        assertFalse(rule.runOnIdle { focus.request(paths[0]) })
        // Only what's composed now, not every game scrolled past.
        val heldNow = rule.runOnIdle { focus.held }
        assertTrue("holds $heldNow, held $shownAtFirst at first", heldNow <= shownAtFirst + 2)
    }

    @Test
    fun aGameScrolledBackTakesTheFocusAgain() {
        show()
        scrollTo(150)
        scrollTo(0)

        assertTrue(rule.runOnIdle { focus.request(paths[0]) })
        rule.runOnIdle { assertEquals(paths[0], focus.focused) }
    }
}
