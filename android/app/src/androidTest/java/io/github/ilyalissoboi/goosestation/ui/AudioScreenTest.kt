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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.settings.AudioSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Audio page with a D-pad. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AudioScreenTest {
    @get:Rule val rule = createComposeRule()

    private val changes = mutableListOf<SettingValues>()

    private fun show(vararg file: Pair<String, String>) {
        rule.setContent {
            GooseStationTheme {
                var values by remember { mutableStateOf(pageValues(AudioSettings.all, *file)) }
                AudioScreen(
                    values,
                    SampleNames.ALL,
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

    @Test
    fun theRowsAreInOrder() {
        show()

        rule.onNode(isFocused()).assert(hasText("Output volume", substring = true))
        for (row in listOf(
            "Fast-forward volume",
            "Mute",
            "Mute CD audio",
            "Stretch mode",
            "Buffer size",
            "Minimal output latency",
            "Output latency",
        )) {
            press(Key.DirectionDown)
            rule.onNode(isFocused()).assert(hasText(row, substring = true))
        }
        for (section in listOf("VOLUME", "OUTPUT")) rule.onNodeWithText(section).assertExists()
    }

    @Test
    fun theFastForwardVolumeStopsAtAHundred() {
        show()

        press(Key.DirectionDown)
        press(Key.DirectionRight)

        assertEquals(emptyList<SettingValues>(), changes)
        rule.onNode(isFocused()).assert(hasText("100%", substring = true))
    }

    @Test
    fun theOutputLatencyGoesWithMinimalLatency() {
        show("Audio/OutputLatencyMinimal" to "true")

        rule.onNodeWithText("Output latency").assertDoesNotExist()
    }
}
