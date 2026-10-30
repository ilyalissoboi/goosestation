// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
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

/** The Controllers page with a D-pad: one controller, with three of Port 1's inputs. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ControllersScreenTest {
    private companion object {
        val PAD = ConnectedController(deviceId = 5, name = "Test Pad", descriptor = "pad-a")
        val OTHER_PAD = ConnectedController(deviceId = 6, name = "Other Pad", descriptor = "pad-b")
        val BINDINGS =
            listOf(
                PadBinding("Up", "D-Pad Up", PadBindingKind.Button, "Button19"),
                PadBinding("Cross", "Cross", PadBindingKind.Button, "Button96"),
                PadBinding("L2", "L2", PadBindingKind.Button, null),
            )
    }

    @get:Rule val rule = createComposeRule()

    private val selects = mutableListOf<String>()
    private val captures = mutableListOf<String>()
    private val clears = mutableListOf<String>()
    private var cancels = 0
    private var resets = 0
    private var backs = 0

    private fun show(
        controllers: List<ConnectedController> = listOf(PAD),
        bindings: List<PadBinding> = BINDINGS,
        capturing: String? = null,
        secondsLeft: Int = 0,
    ) {
        rule.setContent {
            GooseStationTheme {
                ControllersScreen(
                    controllers = controllers,
                    selected = controllers.firstOrNull()?.descriptor,
                    bindings = bindings,
                    capturing = capturing,
                    secondsLeft = secondsLeft,
                    onSelect = { selects += it },
                    onCapture = { captures += it },
                    onCancelCapture = { cancels++ },
                    onClear = { clears += it },
                    onReset = { resets++ },
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

    private fun assertFocusedHas(vararg texts: String) {
        texts.forEach { rule.onNode(isFocused()).assert(hasText(it, substring = true)) }
    }

    @Test
    fun theFirstBindingIsFocusedOnOpening() {
        show()

        assertFocusedHas("D-Pad Up", "D-pad up")
    }

    @Test
    fun withTwoControllersTheControllerRowIsFocused() {
        show(controllers = listOf(PAD, OTHER_PAD))

        assertFocusedHas("Controller", "Test Pad")
    }

    @Test
    fun oneControllerIsNamed() {
        show()

        rule.onNode(hasText("Test Pad")).assertExists()
        rule.onAllNodes(hasText("Controller")).assertCountEquals(0)
        rule.onNode(hasText("Every connected controller drives Port 1. Each one keeps its own mapping.")).assertExists()
    }

    @Test
    fun anUnboundInputIsNotSet() {
        show()

        press(Key.DirectionDown)
        press(Key.DirectionDown)

        assertFocusedHas("L2", "Not set")
    }

    @Test
    fun enterStartsACapture() {
        show()

        press(Key.Enter)

        assertEquals(listOf("Up"), captures)
    }

    @Test
    fun aCapturingRowSaysWhatToDo() {
        show(capturing = "Up", secondsLeft = 4)

        assertFocusedHas("D-Pad Up", "Press a button or move a stick on Test Pad… (4)")
    }

    @Test
    fun backCancelsACapture() {
        show(capturing = "Up", secondsLeft = 4)

        press(Key.Back)

        assertEquals(1, cancels)
        assertEquals(0, backs)
    }

    @Test
    fun backGoesBackWithNoCapture() {
        show()

        press(Key.Back)

        assertEquals(0, cancels)
        assertEquals(1, backs)
    }

    @Test
    fun clearIsReachedWithRight() {
        show()

        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertFocusedHas("Clear")
        press(Key.Enter)

        assertEquals(listOf("Cross"), clears)
        assertEquals(emptyList<String>(), captures)
    }

    @Test
    fun resetToDefaultsIsLast() {
        show()

        repeat(3) { press(Key.DirectionDown) }
        assertFocusedHas("Reset to defaults")
        press(Key.Enter)

        assertEquals(1, resets)
    }

    @Test
    fun noControllerSaysToConnectOne() {
        show(controllers = emptyList(), bindings = emptyList())

        rule.onNode(hasText("Connect a controller to remap it.")).assertExists()
        rule.onAllNodes(hasText("Reset to defaults")).assertCountEquals(0)
        rule.onAllNodes(hasText("Every connected controller", substring = true)).assertCountEquals(0)

        // The line holds the focus, so B still goes back.
        press(Key.ButtonB)
        assertEquals(1, backs)
    }

    @Test
    fun theMenuTriggersAreShown() {
        show()

        rule
            .onNode(
                hasText(
                    "Back, the Mode (Home or Guide) button, or holding Select + Start for a second pauses the game " +
                        "and opens the menu. These buttons always do this, whatever is mapped to them.",
                ),
            ).assertExists()
    }
}
