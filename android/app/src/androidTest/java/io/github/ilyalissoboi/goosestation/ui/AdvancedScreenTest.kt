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
import io.github.ilyalissoboi.goosestation.settings.AdvancedSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Advanced page with a D-pad. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AdvancedScreenTest {
    @get:Rule val rule = createComposeRule()

    private val changes = mutableListOf<SettingValues>()

    private fun show(fastmemAvailable: Boolean = true) {
        rule.setContent {
            GooseStationTheme {
                var values by remember { mutableStateOf(pageValues(AdvancedSettings.all)) }
                AdvancedScreen(
                    values,
                    SampleNames.ALL,
                    fastmemAvailable,
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
    fun theRowsInTheirSections() {
        show()

        rule.onNode(isFocused()).assert(hasText("Log level", substring = true))
        for (row in listOf(
            "Log to file",
            "Save state compression",
            "Load devices from save states",
            "CD-ROM region check",
            "Allow booting without an SBI file",
            "Recompiler memory exceptions",
            "Recompiler block linking",
            "Recompiler fast memory access",
        )) {
            press(Key.DirectionDown)
            rule.onNode(isFocused()).assert(hasText(row, substring = true))
        }
        for (section in listOf("LOGGING", "SAVE STATES", "CD-ROM", "CPU")) rule.onNodeWithText(section).assertExists()
    }

    @Test
    fun aChangeWritesDesktopsKey() {
        show()

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)

        assertEquals("ZstHigh", changes[0][AdvancedSettings.SAVE_STATE_COMPRESSION])
        assertEquals(true, changes[1][AdvancedSettings.REGION_CHECK])
    }

    @Test
    fun fastMemoryIsOffOnLargePages() {
        show(fastmemAvailable = false)

        rule.onNodeWithText("Recompiler fast memory access", substring = true).assertIsNotEnabled()
        rule.onNodeWithText(FASTMEM_UNAVAILABLE, substring = true).assertExists()
    }
}
