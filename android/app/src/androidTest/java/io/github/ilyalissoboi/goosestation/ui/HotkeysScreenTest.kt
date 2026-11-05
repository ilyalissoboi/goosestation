// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Hotkeys page with a D-pad: the core's hotkeys under its categories, each a Select chord. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HotkeysScreenTest {
    @get:Rule val rule = createComposeRule()

    private val captures = mutableListOf<String>()
    private val clears = mutableListOf<String>()
    private var backs = 0

    private fun show(
        controllers: List<ConnectedController> = listOf(PAD),
        hotkeys: List<PadBinding> = HOTKEYS,
        capturing: String? = null,
        hotkeysFor: String? = controllers.firstOrNull()?.descriptor,
    ) {
        rule.setContent {
            GooseStationTheme {
                HotkeysScreen(
                    controllers = controllers,
                    labels = emptyMap(),
                    selected = controllers.firstOrNull()?.descriptor,
                    hotkeys = hotkeys,
                    hotkeysFor = hotkeysFor,
                    capturing = capturing,
                    secondsLeft = 5,
                    undoFor = null,
                    onSelect = {},
                    onCaptureHotkey = { captures += it },
                    onCancelCapture = {},
                    onClear = { clears += it },
                    onUndo = {},
                    onDropUndo = {},
                    onBack = { backs++ },
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
    fun theCategoriesAndNamesAreTheCores() {
        show()

        // The headings show in small capitals.
        for (heading in listOf("General", "System", "Save States", "Controller")) {
            rule.onNode(hasText(heading, ignoreCase = true) and isHeading()).performScrollTo().assertIsDisplayed()
        }
        for (name in listOf("Toggle Fast Forward", "Rewind", "Save To Selected Slot")) {
            rule.onNodeWithText(name).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("Select + X").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun opensOnTheControllerAndStepsToTheFirstHotkey() {
        show()

        rule.onNode(isFocused()).assert(hasText("Test Pad"))
        press(Key.DirectionDown)
        rule.onNode(isFocused()).assert(hasText("Toggle Fast Forward", substring = true))
    }

    @Test
    fun aRowCapturesAHotkey() {
        show()

        rule.onNodeWithText("Save To Selected Slot").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(listOf("SaveSelectedSaveState"), captures)
    }

    @Test
    fun aCapturePromptsWithTheCoresName() {
        show(capturing = "Rewind")

        rule
            .onNodeWithText(
                "Hold Select and press a button for Rewind… Back cancels (5)",
            ).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun noControllerSaysToConnectOne() {
        show(controllers = emptyList())

        rule.onNodeWithText("Connect a controller to set its hotkeys.").assertExists()
        press(Key.ButtonB)
        assertEquals(1, backs)
    }

    @Test
    fun anotherControllersHotkeysAreReadFirst() {
        show(hotkeysFor = "pad-b")

        rule.onNodeWithText("Reading Test Pad's hotkeys…").assertExists()
        rule.onAllNodesWithText("Rewind").assertCountEquals(0)
    }

    private companion object {
        val PAD = ConnectedController(deviceId = 5, name = "Test Pad", descriptor = "pad-a", motors = 2)
        val HOTKEYS =
            listOf(
                PadBinding(
                    "ToggleFastForward",
                    "Toggle Fast Forward",
                    PadBindingKind.Button,
                    "Button109 & Android-0/Button99",
                    "General",
                ),
                PadBinding("Screenshot", "Save Screenshot", PadBindingKind.Button, null, "General"),
                PadBinding("Rewind", "Rewind", PadBindingKind.Button, null, "System"),
                PadBinding(
                    "SaveSelectedSaveState",
                    "Save To Selected Slot",
                    PadBindingKind.Button,
                    null,
                    "Save States",
                ),
                PadBinding("ToggleAnalogMode", "Toggle Analog Mode", PadBindingKind.Button, null, "Controller"),
            )
    }
}
