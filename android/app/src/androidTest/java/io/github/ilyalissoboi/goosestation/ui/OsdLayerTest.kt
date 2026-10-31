// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.os.SystemClock
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.OsdMessage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The messages over the game: top left, in order, the fast-forward marker top right, nothing focusable. */
@RunWith(AndroidJUnit4::class)
class OsdLayerTest {
    @get:Rule val rule = createComposeRule()

    private fun message(
        id: Long,
        text: String,
    ) = OsdMessage(id, null, text, null, Long.MAX_VALUE)

    private fun show(
        messages: List<OsdMessage>,
        fastForward: Boolean = false,
        nextExpiry: Long? = null,
        onExpire: () -> Unit = {},
    ) {
        rule.setContent {
            GooseStationTheme { OsdLayer(messages, fastForward, nextExpiry, onExpire) }
        }
        rule.waitForIdle()
    }

    @Test
    fun messagesShowTopLeftInOrder() {
        show(listOf(message(1, "Saved to slot 1"), message(2, "Memory card saved.")))

        val first = rule.onNodeWithText("Saved to slot 1").getUnclippedBoundsInRoot()
        val second = rule.onNodeWithText("Memory card saved.").getUnclippedBoundsInRoot()
        assertTrue("the second is below the first", second.top >= first.bottom)
        assertTrue("the first is at the left", first.left <= 32.dp)
    }

    @Test
    fun theMarkerShowsWhileFastForwardIsOn() {
        show(emptyList(), fastForward = true)

        rule.onNodeWithContentDescription("Fast forward on").assertExists()
    }

    @Test
    fun theMarkerIsGoneWhileFastForwardIsOff() {
        show(emptyList(), fastForward = false)

        rule.onNodeWithContentDescription("Fast forward on").assertDoesNotExist()
    }

    @Test
    fun nothingIsFocusable() {
        show(listOf(message(1, "Console reset")), fastForward = true)

        assertEquals(0, rule.onAllNodes(isFocusable()).fetchSemanticsNodes().size)
    }

    @Test
    fun expiryCallsBack() {
        var expired = 0
        rule.mainClock.autoAdvance = false
        show(
            listOf(message(1, "Console reset")),
            nextExpiry = SystemClock.uptimeMillis() + 200,
            onExpire = { expired++ },
        )

        rule.mainClock.advanceTimeBy(300)
        rule.waitUntil(2_000) { expired > 0 }

        assertEquals(1, expired)
    }
}
