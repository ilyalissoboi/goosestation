// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The game's prompt with a controller: Resume first, and B, Back or Mode resume, acting only on presses it saw. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GamePromptTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var resumes = 0
    private var quits = 0

    private fun show(game: GameEntry? = null) {
        rule.setContent {
            GooseStationTheme { GamePrompt(onResume = { resumes++ }, onQuit = { quits++ }, game = game) }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertCalls(
        resumes: Int,
        quits: Int,
    ) {
        assertEquals("resumes", resumes, this.resumes)
        assertEquals("quits", quits, this.quits)
    }

    @Test
    fun resumeIsFocusedWhenItOpens() {
        show()

        rule.onNodeWithText("PAUSED").assertExists()
        rule.onNodeWithText("Resume").assertIsFocused()
    }

    @Test
    fun itNamesTheGame() {
        show(
            GameEntry(
                path = "content://test/tree/games/document/crash.cue",
                title = "Crash Bandicoot",
                serial = "SCUS-94900",
                region = "NTSC-U",
                totalPlayedSeconds = 3 * 3600 + 12 * 60,
                lastPlayedSeconds = 0,
                coverPath = null,
            ),
        )

        rule.onNodeWithText("PAUSED").assertExists()
        rule.onNodeWithText("Crash Bandicoot").assertExists()
        rule.onNodeWithText("NTSC-U · SCUS-94900 · 3h 12m").assertExists()
    }

    @Test
    fun backResumes() {
        show()

        press(Key.Back)

        assertCalls(resumes = 1, quits = 0)
    }

    @Test
    fun buttonBResumes() {
        show()

        press(Key.ButtonB)

        assertCalls(resumes = 1, quits = 0)
    }

    @Test
    fun downThenEnterQuits() {
        show()

        press(Key.DirectionDown)
        rule.onNodeWithText("Quit to library").assertIsFocused()
        press(Key.Enter)

        assertCalls(resumes = 0, quits = 1)
    }

    @Test
    fun modeResumes() {
        show()

        press(Key.ButtonMode)

        assertCalls(resumes = 1, quits = 0)
    }

    @Test
    fun theKeyUpOfThePressThatOpenedItDoesNothing() {
        // The presses begin on what was there before the prompt, and end on the prompt.
        var open by mutableStateOf(false)
        rule.setContent {
            GooseStationTheme {
                if (open) {
                    GamePrompt(onResume = { resumes++ }, onQuit = { quits++ })
                } else {
                    val before = remember { FocusRequester() }
                    LaunchedEffect(Unit) { before.requestFocus() }
                    Box(Modifier.size(10.dp).focusRequester(before).focusable())
                }
            }
        }
        rule.waitForIdle()
        val keys = listOf(Key.ButtonMode, Key.ButtonB, Key.Back, Key.ButtonStart)
        rule.onRoot().performKeyInput { keys.forEach { keyDown(it) } }
        open = true
        rule.waitForIdle()
        rule.onNodeWithText("Resume").assertIsFocused()

        rule.onRoot().performKeyInput { keys.forEach { keyUp(it) } }
        rule.waitForIdle()

        assertCalls(resumes = 0, quits = 0)
        rule.onNodeWithText("Resume").assertIsFocused()
        // A press of its own still counts.
        press(Key.ButtonMode)
        assertCalls(resumes = 1, quits = 0)
    }

    @Test
    fun backOpensThePromptInTheGameThenResumesIt() {
        var promptOpen by mutableStateOf(false)
        rule.setContent {
            GooseStationTheme {
                GameScreen(
                    promptOpen = promptOpen,
                    onOpenPrompt = { promptOpen = true },
                    onResume = {
                        resumes++
                        promptOpen = false
                    },
                    onQuit = { quits++ },
                )
            }
        }
        rule.waitForIdle()

        // Back reaches the game screen from the activity, as the system's Back does.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Resume").assertIsFocused()
        assertCalls(resumes = 0, quits = 0)

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertCalls(resumes = 1, quits = 0)
        rule.onNodeWithText("Resume").assertDoesNotExist()
    }
}
