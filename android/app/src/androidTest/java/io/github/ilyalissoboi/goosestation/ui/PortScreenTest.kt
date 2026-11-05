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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind
import io.github.ilyalissoboi.goosestation.core.TypeSetting
import io.github.ilyalissoboi.goosestation.input.BindingLabels
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.BoolSetting
import io.github.ilyalissoboi.goosestation.settings.FloatSetting
import io.github.ilyalissoboi.goosestation.settings.MacroSettings
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A port's page with a D-pad: one controller, with three of its inputs. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PortScreenTest {
    private companion object {
        val PAD = ConnectedController(deviceId = 5, name = "Test Pad", descriptor = "pad-a")
        val OTHER_PAD = ConnectedController(deviceId = 6, name = "Other Pad", descriptor = "pad-b")
        val TYPES =
            listOf(
                Choice("None", "Not Connected"),
                Choice("AnalogController", "Analog Controller (DualShock)"),
                Choice("NeGcon", "NeGcon"),
            )

        val DEADZONE =
            TypeSetting(
                TypeSetting.Kind.Float,
                "AnalogDeadzone",
                "Analog Deadzone",
                "Ignores small pushes.",
                "0.00",
                "0.00",
                "1.00",
                "0.01",
                "%.0f%%",
                100f,
                emptyList(),
            )
        val INVERT =
            TypeSetting(
                TypeSetting.Kind.IntList,
                "InvertLeftStick",
                "Invert Left Stick",
                "",
                "0",
                "0",
                "3",
                "1",
                "",
                1f,
                listOf("Not Inverted", "Invert Left/Right", "Invert Up/Down", "Invert Left/Right + Up/Down"),
            )
        val FORCE =
            TypeSetting(
                TypeSetting.Kind.Bool,
                "ForceAnalogOnReset",
                "Force Analog Mode on Reset",
                "",
                "true",
                "",
                "",
                "",
                "",
                1f,
                emptyList(),
            )
        val SETTINGS = listOf(DEADZONE, INVERT, FORCE)

        fun portValues(vararg file: Pair<String, String>) =
            pageValues(PortSettings.settings(0, SETTINGS) + (1..4).map { MacroSettings.binds(0, it) }, *file)

        val BINDINGS =
            listOf(
                PadBinding("Up", "D-Pad Up", PadBindingKind.Button, "Button19"),
                PadBinding("Cross", "Cross", PadBindingKind.Button, "Button96"),
                PadBinding("L2", "L2", PadBindingKind.Button, null),
            )

        // With an axis that has a glyph, and an input that has none.
        val GLYPH_BINDINGS =
            BINDINGS +
                listOf(
                    PadBinding("LLeft", "Left Stick Left", PadBindingKind.HalfAxis, null),
                    PadBinding("Turbo", "Turbo", PadBindingKind.Button, null),
                )
    }

    @get:Rule val rule = createComposeRule()

    private val selects = mutableListOf<String>()
    private val types = mutableListOf<String>()
    private val changes = mutableListOf<SettingValues>()
    private val macros = mutableListOf<Int>()
    private val captures = mutableListOf<String>()
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
        // Words, which most tests find the rows by, unless a test asks for the glyphs the page shows by default.
        glyphs: Boolean = false,
        type: String? = null,
        typeSettings: List<TypeSetting> = emptyList(),
        values: SettingValues? = null,
    ) {
        this.controllers = controllers
        this.capturing = capturing
        this.undoFor = undoFor
        rule.setContent {
            GooseStationTheme {
                PortScreen(
                    title = "Port 1",
                    controllers = this.controllers,
                    labels = labels,
                    selected = selected,
                    bindings = bindings,
                    bindingsFor = bindingsFor,
                    capturing = this.capturing,
                    secondsLeft = secondsLeft,
                    undoFor = this.undoFor,
                    onSelect = { selects += it },
                    onCapture = { captures += it },
                    onCancelCapture = { cancels++ },
                    onClear = { clears += it },
                    onUndo = {
                        undos++
                        this.undoFor = null
                    },
                    onDropUndo = { dropUndos++ },
                    onReset = { resets++ },
                    onBack = { backs++ },
                    glyphs = glyphs,
                    pad = 0,
                    type = type,
                    typeNames = TYPES,
                    onType = { types += it },
                    typeSettings = typeSettings,
                    values = values,
                    onChange = { changes += it },
                    onOpenMacro = { macros += it },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun port1ShowsGlyphsWithTheirNamesForScreenReaders() {
        show(bindings = GLYPH_BINDINGS, glyphs = true)

        for (name in listOf("D-Pad Up", "Cross", "L2", "Left Stick Left")) {
            rule.onNodeWithContentDescription(name, useUnmergedTree = true).assertExists()
        }
        // The names aren't shown as words (L2's glyph has "L2" on it, as the controller does).
        for (name in listOf("D-Pad Up", "Cross", "Left Stick Left")) {
            rule.onNode(hasText(name), useUnmergedTree = true).assertDoesNotExist()
        }
        // The row says both: its input, through the glyph, and what it's bound to.
        rule
            .onNode(hasContentDescription("Cross") and hasText(BindingLabels.label("Button96")))
            .assertExists()
    }

    @Test
    fun aNameWithoutAGlyphStaysText() {
        show(bindings = GLYPH_BINDINGS, glyphs = true)

        rule.onNodeWithText("Turbo").assertExists()
    }

    @Test
    fun anotherControllerTypeKeepsText() {
        show(bindings = GLYPH_BINDINGS, glyphs = false)

        rule.onNodeWithText("Cross").assertExists()
        rule.onNodeWithText("Left Stick Left").assertExists()
        rule.onNodeWithContentDescription("Cross").assertDoesNotExist()
    }

    @Test
    fun theCapturePromptKeepsTheWord() {
        show(bindings = GLYPH_BINDINGS, glyphs = true, capturing = "Cross", secondsLeft = 5)

        rule.onNode(hasText("Press the new button for Cross", substring = true)).assertExists()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertFocusedHas(vararg texts: String) {
        texts.forEach { rule.onNode(isFocused()).assert(hasText(it, substring = true)) }
    }

    // --- The type, its settings and the macros ---

    @Test
    fun withATypeThePageOpensOnIt() {
        show(type = "AnalogController", typeSettings = SETTINGS, values = portValues())

        assertFocusedHas("Controller type", "Analog Controller (DualShock)")
        press(Key.DirectionRight)
        assertEquals(listOf("NeGcon"), types)
    }

    // Review Focus 3: a type the page doesn't offer (a desktop GunCon) shows as itself.
    @Test
    fun aTypeFromTheFileShowsAsItself() {
        show(type = "GunCon", values = portValues())

        assertFocusedHas("GunCon (from settings.ini)")
    }

    @Test
    fun aFloatSettingStepsThroughItsFormat() {
        show(type = "AnalogController", typeSettings = SETTINGS, values = portValues("Pad1/AnalogDeadzone" to "0.25"))

        rule.onNodeWithText("Ignores small pushes.").assertExists()
        press(Key.DirectionDown)
        assertFocusedHas("Analog Deadzone", "25%")
        press(Key.DirectionRight)

        assertEquals(0.26f, changes.single()[PortSettings.setting(0, DEADZONE) as FloatSetting], 1e-4f)
    }

    @Test
    fun anOptionListShowsTheCoresOptions() {
        show(type = "AnalogController", typeSettings = SETTINGS, values = portValues("Pad1/InvertLeftStick" to "2"))

        repeat(2) { press(Key.DirectionDown) }
        assertFocusedHas("Invert Left Stick", "Invert Up/Down")
    }

    @Test
    fun aBoolSettingIsASwitch() {
        show(type = "AnalogController", typeSettings = SETTINGS, values = portValues())

        repeat(3) { press(Key.DirectionDown) }
        assertFocusedHas("Force Analog Mode on Reset")
        press(Key.Enter)

        assertEquals(false, changes.single()[PortSettings.setting(0, FORCE) as BoolSetting])
    }

    @Test
    fun aMacroRowSaysWhatItPressesAndOpensIt() {
        show(
            type = "AnalogController",
            typeSettings = SETTINGS,
            values =
                portValues("Pad1/Macro2Binds" to "Cross & Circle"),
        )

        rule.onNodeWithText("Cross & Circle").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Macro 2").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(listOf(2), macros)
        rule.onNodeWithText("Macro 1").assertExists()
    }

    // Below the settings, one controller's name isn't a stop: it has no ring, so Down goes on to the first binding.
    @Test
    fun oneControllersNameIsNoStopBelowTheSettings() {
        show(type = "AnalogController", typeSettings = SETTINGS, values = portValues())

        repeat(4) { press(Key.DirectionDown) }

        assertFocusedHas("D-Pad Up")
    }

    // Nor is the line saying no controller is on the port: Down goes from the settings to the macros.
    @Test
    fun noControllersLineIsNoStopBelowTheSettings() {
        show(
            controllers = emptyList(),
            bindings = emptyList(),
            type = "AnalogController",
            typeSettings = SETTINGS,
            values = portValues(),
        )

        repeat(4) { press(Key.DirectionDown) }

        assertFocusedHas("Macro 1")
    }

    // Not Connected: nothing to bind, set or press.
    @Test
    fun notConnectedHasOnlyTheTypeRow() {
        show(type = "None", values = portValues())

        assertFocusedHas("Controller type", "Not Connected")
        rule.onAllNodes(hasText("Macro 1")).assertCountEquals(0)
        rule.onAllNodes(hasText("Reset to defaults")).assertCountEquals(0)
        rule.onAllNodes(hasText("D-Pad Up")).assertCountEquals(0)
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
        rule.onNode(hasText("Each controller on Port 1 keeps its own bindings.")).assertExists()
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

        rule.onNode(hasText("No controller is on Port 1. Pick Port 1 for one on the Controllers page.")).assertExists()
        rule.onAllNodes(hasText("Reset to defaults")).assertCountEquals(0)
        rule.onAllNodes(hasText("keeps its own bindings", substring = true)).assertCountEquals(0)

        // The line holds the focus, so B still goes back.
        press(Key.ButtonB)
        assertEquals(1, backs)
    }
}
