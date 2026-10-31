// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.ResumeOffer
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/** "Resume where you left off?" over the library: Resume focused, Start over beside it, B cancels, focus kept in it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ResumePromptTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = ZonedDateTime.of(2026, 10, 31, 14, 5, 0, 0, ZoneId.of("Asia/Tokyo"))
    private val crash = GameEntry("content://games/crash.cue", "Crash Bandicoot", "SCUS-94900", "NTSC-U", 0, 0, null)
    private val calls = mutableListOf<String>()

    private fun show() {
        rule.setContent {
            GooseStationTheme {
                Box(Modifier.fillMaxSize()) {
                    // What the library has: focusable things under the prompt.
                    ActionButton(
                        "Settings",
                        onClick = { calls += "settings" },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                    ResumePrompt(
                        ResumeOffer(crash, now.toEpochSecond() - 3_600, null),
                        onResume = { calls += "resume" },
                        onStartOver = { calls += "startOver" },
                        onCancel = { calls += "cancel" },
                        now = now,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun resumeIsFocused() {
        show()

        rule.onNodeWithText("Resume where you left off?").assertExists()
        rule.onNodeWithText("Crash Bandicoot").assertExists()
        rule.onNodeWithText("Resume").assertIsFocused()
    }

    @Test
    fun startOverIsOneStepRight() {
        show()

        press(Key.DirectionRight)
        rule.onNodeWithText("Start over").assertIsFocused()
        press(Key.Enter)

        assertEquals(listOf("startOver"), calls)
    }

    @Test
    fun resumeResumes() {
        show()

        press(Key.Enter)

        assertEquals(listOf("resume"), calls)
    }

    @Test
    fun bCancels() {
        show()

        press(Key.ButtonB)

        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun focusStaysInThePrompt() {
        show()

        for (key in listOf(
            Key.DirectionUp,
            Key.DirectionLeft,
            Key.DirectionDown,
            Key.DirectionRight,
            Key.DirectionUp,
        )) {
            press(key)
            rule.onNode(isFocused() and (hasText("Resume") or hasText("Start over"))).assertExists()
        }
    }

    @Test
    fun itShowsTheTime() {
        show()

        rule.onNodeWithText("Today · 13:05").assertExists()
    }
}
