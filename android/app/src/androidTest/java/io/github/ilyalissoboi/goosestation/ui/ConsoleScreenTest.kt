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
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.settings.ConsoleSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Console page with a D-pad. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ConsoleScreenTest : ControllerKeys {
    @get:Rule override val rule = createComposeRule()

    private val changes = mutableListOf<SettingValues>()

    private fun show(start: SettingValues) {
        rule.setContent {
            GooseStationTheme {
                var values by remember { mutableStateOf(start) }
                ConsoleScreen(
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

    @Test
    fun theSectionsAreInOrder() {
        show(pageValues(ConsoleSettings.all))

        rule.onNode(isFocused()).assert(hasText("Region", substring = true))
        for (row in listOf(
            "8 MB RAM",
            "Disable all enhancements",
            "Execution mode",
            "Overclocking",
            "Recompiler ICache",
            "Read speed-up",
            "Seek speed-up",
            "Readahead",
            "Preload disc to RAM",
            "Apply image patches",
        )) {
            press(Key.DirectionDown)
            rule.onNode(isFocused()).assert(hasText(row, substring = true))
        }
        for (section in listOf("CONSOLE", "CPU", "CD-ROM")) rule.onNodeWithText(section).assertExists()
    }

    @Test
    fun thePercentageShowsWithOverclockingOnAndWritesAFraction() {
        show(pageValues(ConsoleSettings.all))
        rule.onNodeWithText("Overclocking percentage").assertDoesNotExist()

        walkTo("Overclocking")
        press(Key.ButtonA)
        press(Key.DirectionDown)
        rule.onNode(isFocused()).assert(hasText("Overclocking percentage", substring = true))
        press(Key.DirectionRight)

        val last = changes.last()
        assertEquals(11 to 10, last[ConsoleSettings.OVERCLOCK_NUMERATOR] to last[ConsoleSettings.OVERCLOCK_DENOMINATOR])
    }

    @Test
    fun belowAHundredIsNotedInHardcoreMode() {
        show(
            pageValues(
                ConsoleSettings.all,
                "CPU/OverclockEnable" to "true",
                "CPU/OverclockNumerator" to "9",
                "CPU/OverclockDenominator" to "10",
                "Cheevos/Enabled" to "true",
                "Cheevos/ChallengeMode" to "true",
            ),
        )

        rule.onNodeWithText("Off in hardcore mode below 100%.").assertExists()
    }

    @Test
    fun noHardcoreNoteWithoutASignIn() {
        show(
            pageValues(
                ConsoleSettings.all,
                "CPU/OverclockEnable" to "true",
                "CPU/OverclockNumerator" to "9",
                "CPU/OverclockDenominator" to "10",
                "Cheevos/ChallengeMode" to "true",
            ),
        )

        rule.onNodeWithText("Off in hardcore mode below 100%.").assertDoesNotExist()
    }

    @Test
    fun seekSpeedUpStepsDownToInstant() {
        show(pageValues(ConsoleSettings.all))

        walkTo("Seek speed-up")
        rule.onNode(isFocused()).assert(hasText("None (normal speed)", substring = true))
        press(Key.DirectionLeft)

        rule.onNode(isFocused()).assert(hasText("Instant", substring = true))
        assertEquals(0, changes.last()[ConsoleSettings.SEEK_SPEEDUP])
    }
}
