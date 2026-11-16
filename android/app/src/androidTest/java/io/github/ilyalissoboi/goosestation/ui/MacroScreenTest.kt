// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.ControllersState
import io.github.ilyalissoboi.goosestation.app.ControllersView
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.MacroSettings
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A port's macro with a D-pad: the controller's trigger, the inputs it presses, how it repeats. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MacroScreenTest : ControllerKeys {
    @get:Rule override val rule = createComposeRule()

    private val actions = FakeControllersActions()
    private val changes get() = actions.macroChanges
    private val captures get() = actions.captures

    private fun show(
        controllers: List<ConnectedController> = listOf(PAD),
        capturing: String? = null,
        vararg file: Pair<String, String>,
    ) {
        val state =
            ControllersState(
                controllers = controllers,
                selected = controllers.firstOrNull()?.descriptor,
                macros = listOf(PadBinding("Macro1", "Macro 1", PadBindingKind.Button, "Button99")),
                bindingsFor = controllers.firstOrNull()?.descriptor,
                macroInputs = INPUTS,
                macroValues = pageValues(MacroSettings.settings(0, 1), *file),
                capturing = capturing,
                secondsLeft = 5,
                view = ControllersView.Macro(0, 1),
            )
        rule.setContent { GooseStationTheme { MacroScreen(state, actions, onBack = {}) } }
        rule.waitForIdle()
    }

    @Test
    fun opensOnTheControllerThenTheTrigger() {
        show()

        rule.onNode(isFocused()).assert(hasText("Test Pad"))
        press(Key.DirectionDown)
        rule.onNode(isFocused()).assert(hasText("Trigger", substring = true))
        press(Key.Enter)
        assertEquals(listOf("Macro1"), captures)
    }

    @Test
    fun aPressSwitchedOnIsWrittenAsDesktopDoes() {
        show(file = arrayOf("Pad1/Macro1Binds" to "Circle"))

        rule.onNodeWithText("Cross").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals("Cross & Circle", changes.single()[MacroSettings.binds(0, 1)])
    }

    @Test
    fun theRepeatRowSaysWhatItDoes() {
        show()

        rule.onNodeWithText("Doesn't repeat").performScrollTo()
        rule.onNodeWithText("Press to toggle").performScrollTo()
    }

    @Test
    fun theTriggersCapturePrompts() {
        show(capturing = "Macro1")

        rule.onNodeWithText("Press the button for Macro 1… Back cancels (5)").assertExists()
    }

    @Test
    fun noControllerOnThePortStillShowsThePresses() {
        show(controllers = emptyList())

        rule.onNodeWithText("No controller is on Port 1", substring = true).assertExists()
        rule.onNodeWithText("Cross").performScrollTo()
    }

    private companion object {
        val PAD = ConnectedController(deviceId = 5, name = "Test Pad", descriptor = "pad-a", motors = 2)
        val INPUTS = listOf("Up", "Cross", "Circle").map { PadBinding(it, it, PadBindingKind.Button, null) }
    }
}
