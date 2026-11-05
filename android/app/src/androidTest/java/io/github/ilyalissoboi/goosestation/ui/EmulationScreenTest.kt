// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings.REWIND_HOTKEY_NOTE
import io.github.ilyalissoboi.goosestation.settings.RewindUsage
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Emulation page with a D-pad. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EmulationScreenTest {
    @get:Rule val rule = createComposeRule()

    private val changes = mutableListOf<SettingValues>()

    private fun show(vararg file: Pair<String, String>) {
        rule.setContent {
            GooseStationTheme {
                var values by remember { mutableStateOf(pageValues(EmulationSettings.all, *file)) }
                EmulationScreen(
                    values,
                    RewindUsage(40L * 1_048_576, 20L * 1_048_576),
                    onChange = {
                        changes += it
                        values = it
                    },
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedHas(text: String) =
        rule.onAllNodes(isFocused() and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun downTo(text: String) {
        repeat(30) {
            if (focusedHas(text)) return
            press(Key.DirectionDown)
        }
        throw AssertionError("Down never reached $text")
    }

    @Test
    fun theSpeedStepsDesktopsLadderAndIsWrittenAsAFactor() {
        show()

        rule.onNode(isFocused()).assert(hasText("Emulation speed", substring = true))
        press(Key.DirectionRight)

        assertEquals(1.25f, changes.last()[EmulationSettings.EMULATION_SPEED])
        rule.onNode(isFocused()).assert(hasText("125%", substring = true))
    }

    @Test
    fun rewindIsOffInHardcoreMode() {
        show("Cheevos/Enabled" to "true", "Cheevos/ChallengeMode" to "true", "Main/RewindEnable" to "true")

        rule.onNode(hasText("Rewind") and hasText("Off in hardcore mode")).assertIsNotEnabled()
        downTo("Run-ahead")
        press(Key.DirectionUp)
        // From run-ahead, up past the disabled rewind row and the rows it hides.
        rule.onNode(isFocused()).assert(hasText("Skip duplicate frames", substring = true))
        rule.onNodeWithText("Save slots").assertDoesNotExist()
    }

    @Test
    fun aSlowSpeedIsNotedInHardcoreMode() {
        show("Cheevos/Enabled" to "true", "Cheevos/ChallengeMode" to "true", "Main/EmulationSpeed" to "0.5")

        rule.onNodeWithText("Runs at 100% in hardcore mode.").assertExists()
    }

    @Test
    fun theBufferShowsWithReducedLatency() {
        show()
        rule.onNodeWithText("Frame time buffer").assertDoesNotExist()

        downTo("Optimal frame pacing")
        press(Key.ButtonA)
        press(Key.DirectionDown)
        rule.onNode(isFocused()).assert(hasText("Reduce input latency", substring = true))
        press(Key.ButtonA)
        press(Key.DirectionDown)

        rule.onNode(isFocused()).assert(hasText("Frame time buffer", substring = true))
        rule.onNode(isFocused()).assert(hasText("2.0 ms", substring = true))
    }

    @Test
    fun skipDuplicatesGoesWithVsyncAndSync() {
        show("Display/VSync" to "true", "Main/SyncToHostRefreshRate" to "true")

        rule.onNodeWithText("Skip duplicate frames").assertDoesNotExist()
    }

    @Test
    fun theRewindEstimateShows() {
        show("Main/RewindEnable" to "true")

        rule
            .onNodeWithText("Goes back up to 100.0 s, using up to 40 MB of memory and 20 MB of video memory.")
            .assertExists()
    }

    @Test
    fun rewindSaysItNeedsAHotkey() {
        show()

        rule.onNodeWithText(REWIND_HOTKEY_NOTE).assertExists()
    }

    @Test
    fun rewindIsOffWhileRunAheadIsOn() {
        show("Main/RewindEnable" to "true", "Main/RunaheadFrameCount" to "2")

        rule.onNodeWithText("Off while run-ahead is on.").assertExists()
    }
}
