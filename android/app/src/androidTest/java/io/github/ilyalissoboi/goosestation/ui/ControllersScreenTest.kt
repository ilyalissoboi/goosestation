// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

/** The Controllers page with a D-pad: one controller, with three of Port 1's inputs. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ControllersScreenTest {
    private companion object {
        val PAD = ConnectedController(deviceId = 5, name = "Test Pad", descriptor = "pad-a")
        val OTHER_PAD = ConnectedController(deviceId = 6, name = "Other Pad", descriptor = "pad-b")
        val HOTKEYS =
            listOf(
                PadBinding(
                    "ToggleFastForward",
                    "Toggle Fast Forward",
                    PadBindingKind.Button,
                    "Button109 & Android-0/Button99",
                ),
                PadBinding("Analog", "Analog Toggle", PadBindingKind.Button, null),
                PadBinding("Screenshot", "Save Screenshot", PadBindingKind.Button, null),
            )

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
    private val hotkeyCaptures = mutableListOf<String>()
    private val clears = mutableListOf<String>()
    private var cancels = 0
    private var resets = 0
    private var backs = 0
    private var undos = 0
    private var dropUndos = 0

    // What the page shows, which a test can change once it's open.
    private var controllers by mutableStateOf(listOf(PAD))
    private var capturing by mutableStateOf<String?>(null)
    private var undoFor by mutableStateOf<String?>(null)

    private fun show(
        controllers: List<ConnectedController> = listOf(PAD),
        selected: String? = controllers.firstOrNull()?.descriptor,
        bindings: List<PadBinding> = BINDINGS,
        bindingsFor: String? = selected,
        capturing: String? = null,
        secondsLeft: Int = 0,
        undoFor: String? = null,
        labels: Map<String, String> = emptyMap(),
        hotkeys: List<PadBinding> = emptyList(),
    ) {
        this.controllers = controllers
        this.capturing = capturing
        this.undoFor = undoFor
        rule.setContent {
            GooseStationTheme {
                ControllersScreen(
                    controllers = this.controllers,
                    labels = labels,
                    selected = selected,
                    bindings = bindings,
                    hotkeys = hotkeys,
                    bindingsFor = bindingsFor,
                    capturing = this.capturing,
                    secondsLeft = secondsLeft,
                    undoFor = this.undoFor,
                    onSelect = { selects += it },
                    onCapture = { captures += it },
                    onCaptureHotkey = { hotkeyCaptures += it },
                    onCancelCapture = { cancels++ },
                    onClear = { clears += it },
                    onUndo = {
                        undos++
                        this.undoFor = null
                    },
                    onDropUndo = { dropUndos++ },
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

    // F-2: never a binding row, so an A pressed as the page opens captures nothing.
    @Test
    fun opensOnTheControllerRow() {
        show()

        assertFocusedHas("Test Pad")
        press(Key.DirectionDown)
        assertFocusedHas("D-Pad Up", "D-pad up")
    }

    @Test
    fun withTwoControllersTheControllerRowIsFocused() {
        show(controllers = listOf(PAD, OTHER_PAD))

        assertFocusedHas("Controller", "Test Pad")
        press(Key.DirectionDown)
        assertFocusedHas("D-Pad Up")
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

        repeat(3) { press(Key.DirectionDown) }

        assertFocusedHas("L2", "Not set")
    }

    @Test
    fun enterStartsACapture() {
        show()

        press(Key.DirectionDown)
        press(Key.Enter)

        assertEquals(listOf("Up"), captures)
    }

    @Test
    fun theCapturePromptSaysBackCancels() {
        show(capturing = "Up", secondsLeft = 4)

        rule.onNode(hasText("Press the new button for D-Pad Up… Back cancels (4)")).assertExists()
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

        repeat(2) { press(Key.DirectionDown) }
        press(Key.DirectionRight)
        assertFocusedHas("Clear")
        press(Key.Enter)

        assertEquals(listOf("Cross"), clears)
        assertEquals(emptyList<String>(), captures)
    }

    @Test
    fun resetToDefaultsIsLast() {
        show()

        repeat(4) { press(Key.DirectionDown) }
        assertFocusedHas("Reset to defaults")
        press(Key.Enter)

        assertEquals(1, resets)
    }

    @Test
    fun undoReplacesClearAfterACapture() {
        show(undoFor = "Cross")

        rule.onAllNodes(hasText("Clear")).assertCountEquals(2)
        repeat(2) { press(Key.DirectionDown) }
        press(Key.DirectionRight)
        assertFocusedHas("Undo")
        press(Key.Enter)

        assertEquals(1, undos)
        assertEquals(emptyList<String>(), clears)
    }

    // Found by hand: Undo turning back into Clear mustn't lose the focus, which sent it to the top of the page.
    @Test
    fun undoKeepsTheFocusOnItsButton() {
        show(undoFor = "Cross")
        repeat(2) { press(Key.DirectionDown) }
        press(Key.DirectionRight)

        press(Key.Enter)

        assertEquals(1, undos)
        assertFocusedHas("Undone")
    }

    // A second A on the button, as Undo turns back into Clear, mustn't clear what Undo just put back.
    @Test
    fun aSecondPressAfterUndoClearsNothing() {
        show(undoFor = "Cross")
        repeat(2) { press(Key.DirectionDown) }
        press(Key.DirectionRight)
        press(Key.Enter)

        assertFocusedHas("Undone")
        press(Key.Enter)
        assertEquals(emptyList<String>(), clears)

        // Once the focus has left the row, it's Clear again.
        press(Key.DirectionLeft)
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        assertFocusedHas("Clear")
    }

    @Test
    fun leavingTheRowDropsTheUndo() {
        show(undoFor = "Cross")
        repeat(2) { press(Key.DirectionDown) }
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        assertEquals(0, dropUndos)

        press(Key.DirectionDown)

        assertFocusedHas("L2")
        assertEquals(1, dropUndos)
    }

    // Review Focus 3: a pad connecting moves no focus already in the page, nor a capture's row.
    @Test
    fun aPadConnectingKeepsTheFocus() {
        show()
        repeat(3) { press(Key.DirectionDown) }
        assertFocusedHas("L2")

        controllers = listOf(PAD, OTHER_PAD)
        rule.waitForIdle()
        assertFocusedHas("L2")

        capturing = "L2"
        rule.waitForIdle()
        controllers = listOf(PAD)
        rule.waitForIdle()
        assertFocusedHas("L2", "Press the new button for L2")
    }

    // T7-3: another pad's bindings never show under this one's name.
    @Test
    fun switchingPadsShowsReadingNotOldBindings() {
        show(controllers = listOf(PAD, OTHER_PAD), selected = OTHER_PAD.descriptor, bindingsFor = PAD.descriptor)

        rule.onNode(hasText("Reading Other Pad's bindings…")).assertExists()
        rule.onAllNodes(hasText("D-Pad Up")).assertCountEquals(0)
        assertFocusedHas("Controller", "Other Pad")
    }

    // T7-1: pads that share a name are told apart by their labels.
    @Test
    fun theLabelsNameThePads() {
        show(
            controllers = listOf(PAD, OTHER_PAD),
            labels = mapOf(PAD.descriptor to "Test Pad (1)", OTHER_PAD.descriptor to "Other Pad (2)"),
        )

        assertFocusedHas("Controller", "Test Pad (1)")
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

    @Test
    fun theHotkeysSectionFollowsThePad() {
        show(hotkeys = HOTKEYS)

        rule.onNodeWithText("Hotkeys").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Select + X").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Fast forward").assertExists()
        rule.onNodeWithText("Analog mode").assertExists()
        rule.onNodeWithText("Screenshot").assertExists()
    }

    @Test
    fun aHotkeyRowCapturesAHotkey() {
        show(hotkeys = HOTKEYS)

        rule.onNodeWithText("Screenshot").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(listOf("Screenshot"), hotkeyCaptures)
        assertEquals(emptyList<String>(), captures)
    }

    @Test
    fun aHotkeyCapturePrompts() {
        show(capturing = "Screenshot", secondsLeft = 5, hotkeys = HOTKEYS)

        rule.onNodeWithText("Hold Select and press a button for Screenshot… Back cancels (5)").assertExists()
    }
}
