// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.settings.InterfaceSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Interface page with a D-pad: four switches, each its own setting. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class InterfaceScreenTest {
    @get:Rule val rule = createComposeRule()

    private val start = pageValues(InterfaceSettings.all)
    private val changes = mutableListOf<SettingValues>()

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun eachSwitchChangesItsOwnSetting() {
        rule.setContent {
            GooseStationTheme { InterfaceScreen(start, onChange = { changes += it }, onBack = {}) }
        }
        rule.waitForIdle()

        val rows =
            listOf(
                "Pause on start" to InterfaceSettings.START_PAUSED,
                "Pause when a controller disconnects" to InterfaceSettings.PAUSE_ON_DISCONNECT,
                "Save state on exit" to InterfaceSettings.SAVE_STATE_ON_EXIT,
                "Create save state backups" to InterfaceSettings.SAVE_STATE_BACKUPS,
            )
        for ((index, row) in rows.withIndex()) {
            if (index > 0) press(Key.DirectionDown)
            rule.onNode(isFocused()).assert(hasText(row.first, substring = true))
            if (row.second == InterfaceSettings.SAVE_STATE_ON_EXIT) {
                rule.onNode(isFocused()).assertIsOn()
            } else {
                rule.onNode(isFocused()).assertIsOff()
            }
            press(Key.ButtonA)
        }

        assertEquals(rows.map { start.with(it.second, !start[it.second]) }, changes)
    }
}
