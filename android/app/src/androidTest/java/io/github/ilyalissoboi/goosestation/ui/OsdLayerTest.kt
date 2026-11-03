// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.AchievementNotice
import io.github.ilyalissoboi.goosestation.app.AchievementOverlay
import io.github.ilyalissoboi.goosestation.app.CardKind
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

    private val unlock =
        AchievementNotice.Card(CardKind.Unlock, "Sparx Rescue", "Free the first dragon", "", 5)

    private fun showOverlay(
        card: AchievementNotice.Card? = unlock,
        indicators: List<AchievementOverlay.Indicator> = emptyList(),
        width: Int = 960,
    ) {
        rule.setContent {
            GooseStationTheme {
                Box(Modifier.requiredSize(width.dp, 540.dp).testTag("screen")) {
                    OsdLayer(emptyList(), false, null, {}, card = card, indicators = indicators)
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun anUnlockCardShowsAtTheTopCentre() {
        showOverlay()

        val screen = rule.onNodeWithTag("screen").getUnclippedBoundsInRoot()
        val card = rule.onNodeWithText("Sparx Rescue").getUnclippedBoundsInRoot()
        assertTrue("near the top", card.top - screen.top < 120.dp)
        val middle = (card.left + card.right) / 2 - screen.left
        assertTrue("centred: $middle", middle > 330.dp && middle < 630.dp)
    }

    @Test
    fun anUnlockCardSaysWhatItIs() {
        showOverlay()

        rule.onNodeWithText("ACHIEVEMENT UNLOCKED").assertExists()
        rule.onNodeWithText("Free the first dragon").assertExists()
        rule.onNodeWithText("5").assertExists()
    }

    @Test
    fun aMasteryCardSaysSo() {
        showOverlay(AchievementNotice.Card(CardKind.Mastery, "Mastered Spyro", "40 achievements", "", null))

        rule.onNodeWithText("SET MASTERED").assertExists()
        rule.onNodeWithText("Mastered Spyro").assertExists()
    }

    @Test
    fun indicatorsShowAtTheBottomRight() {
        showOverlay(
            card = null,
            indicators = listOf(AchievementOverlay.Indicator(AchievementOverlay.TRACKER, 1, "0:12.3", "")),
        )

        val screen = rule.onNodeWithTag("screen").getUnclippedBoundsInRoot()
        val tracker = rule.onNodeWithText("0:12.3").getUnclippedBoundsInRoot()
        assertTrue("at the bottom", screen.bottom - tracker.bottom < 100.dp)
        assertTrue("at the right", screen.right - tracker.right < 100.dp)
    }

    @Test
    fun aLongCardWrapsOnANarrowScreen() {
        showOverlay(
            AchievementNotice.Card(
                CardKind.Unlock,
                "Collect every gem in every world without losing a single life",
                "In one sitting, from a new game, on the hardest difficulty the game has",
                "",
                25,
            ),
            width = 360,
        )

        val screen = rule.onNodeWithTag("screen").getUnclippedBoundsInRoot()
        val text = rule.onNodeWithText("In one sitting", substring = true).getUnclippedBoundsInRoot()
        assertTrue("inside the screen", text.right <= screen.right && text.left >= screen.left)
    }

    @Test
    fun theOverlayIsntFocusable() {
        showOverlay(indicators = listOf(AchievementOverlay.Indicator(AchievementOverlay.CHALLENGE, 1, "", "")))

        assertEquals(0, rule.onAllNodes(isFocusable()).fetchSemanticsNodes().size)
    }

    @Test
    fun cardShowsTheNote() {
        showOverlay(unlock.copy(note = "Sent when you're online"))

        rule.onNodeWithText("Sent when you're online").assertExists()
    }
}
