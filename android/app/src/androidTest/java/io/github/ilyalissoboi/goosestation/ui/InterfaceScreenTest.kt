// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.CheckNowState
import io.github.ilyalissoboi.goosestation.settings.InterfaceSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.update.CheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Interface page with a D-pad: four switches, each its own setting. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class InterfaceScreenTest : ControllerKeys {
    @get:Rule override val rule = createComposeRule()

    private val start = pageValues(InterfaceSettings.all)
    private val changes = mutableListOf<SettingValues>()

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

    private var checks = 0

    private fun showWithUpdates(
        check: CheckNowState = CheckNowState.Idle,
        updates: Boolean = true,
    ) {
        rule.setContent {
            GooseStationTheme {
                InterfaceScreen(
                    start,
                    onChange = { changes += it },
                    onBack = {},
                    updates = if (updates) InterfaceUpdates(check, onCheckNow = { checks++ }) else null,
                    version = "0.1-575-g9c532cd4f",
                )
            }
        }
        rule.waitForIdle()
    }

    private fun shown(text: String) =
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun updateRowsShowWithAModel() {
        showWithUpdates()
        for (text in listOf("Check for updates at start", "Update channel", "Preview", "Check now", "Version")) {
            assertTrue(text, shown(text))
        }
        assertTrue(shown(breakable("0.1-575-g9c532cd4f")))
    }

    @Test
    fun noModelShowsOnlyTheVersion() {
        showWithUpdates(updates = false)
        assertFalse(shown("Check now"))
        assertFalse(shown("Update channel"))
        assertFalse(shown("Check for updates at start"))
        assertTrue(shown(breakable("0.1-575-g9c532cd4f")))
        assertTrue(shown("This build doesn't check for updates."))
    }

    @Test
    fun checkNowCallsBack() {
        showWithUpdates()
        rule.onNodeWithText("Check now").performClick()
        assertEquals(1, checks)
    }

    @Test
    fun checkingSaysSoAndWaits() {
        showWithUpdates(CheckNowState.Checking)
        assertTrue(shown("Checking for updates…"))
        rule.onNodeWithText("Check now").performClick()
        assertEquals(0, checks)
    }

    // From review: disabled while checking, Check now lost the focus for the whole check, and it never came back.
    @Test
    fun checkNowKeepsTheFocusWhileChecking() {
        var check by mutableStateOf<CheckNowState>(CheckNowState.Idle)
        rule.setContent {
            GooseStationTheme {
                InterfaceScreen(start, onChange = {}, onBack = {}, updates = InterfaceUpdates(check) { checks++ })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Check now").requestFocus()
        rule.waitForIdle()
        check = CheckNowState.Checking
        rule.waitForIdle()
        rule.onNodeWithText("Check now").assertIsFocused()
        check = CheckNowState.Done(CheckResult.UpToDate)
        rule.waitForIdle()
        rule.onNodeWithText("Check now").assertIsFocused()
    }

    @Test
    fun aResultShowsUnderCheckNow() {
        showWithUpdates(CheckNowState.Done(CheckResult.UpToDate))
        assertTrue(shown("GooseStation is up to date."))
    }

    @Test
    fun theChannelCyclesPreviewAndRolling() {
        showWithUpdates()
        repeat(10) {
            if (rule
                    .onAllNodes(
                        isFocused().and(hasText("Update channel", substring = true)),
                    ).fetchSemanticsNodes()
                    .isEmpty()
            ) {
                press(Key.DirectionDown)
            }
        }
        rule.onNode(isFocused()).assert(hasText("Update channel", substring = true))
        press(Key.DirectionRight)
        assertEquals("latest", changes.last()[InterfaceSettings.UPDATE_CHANNEL])
    }
}
