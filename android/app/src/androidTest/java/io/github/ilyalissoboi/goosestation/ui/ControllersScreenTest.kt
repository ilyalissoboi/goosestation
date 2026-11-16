// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.ControllersState
import io.github.ilyalissoboi.goosestation.app.ControllersView
import io.github.ilyalissoboi.goosestation.app.TouchMode
import io.github.ilyalissoboi.goosestation.app.TouchSettings
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Controllers page's hub with a D-pad: each controller's port and rumble, multitap, the ports and hotkeys. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ControllersScreenTest : ControllerKeys {
    @get:Rule override val rule = createComposeRule()

    @get:Rule val deviceKeys = DeviceKeys()

    private val calls = mutableListOf<String>()

    private fun show(
        controllers: List<ConnectedController> = listOf(PAD),
        ports: Map<String, Int> = emptyMap(),
        offered: List<Choice<Int>> = OFFERED,
        cameFrom: ControllersView? = null,
    ) {
        rule.setContent {
            GooseStationTheme {
                ControllersScreen(
                    ControllersState(
                        controllers = controllers,
                        ports = ports,
                        rumble = mapOf(PAD.descriptor to 60),
                        offered = offered,
                        portTypes = mapOf(0 to "AnalogController", 1 to "None"),
                        typeNames = TYPES,
                        multitap = MULTITAP,
                        multitapNames = MODES,
                        cameFrom = cameFrom,
                    ),
                    FakeControllersActions(calls),
                    onBack = { calls += "back" },
                    touch = TouchSettings(),
                    onTouch = { calls += "touch $it" },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun assertFocusedHas(vararg texts: String) {
        texts.forEach { rule.onNode(isFocused()).assert(hasText(it, substring = true)) }
    }

    @Test
    fun touchRowsChangeTheSettings() {
        show()
        repeat(2) { press(Key.DirectionDown) }
        assertFocusedHas("Touch controls", "Automatic")
        rule.onNodeWithText("Automatic shows them in a game while no controller is connected.").assertExists()
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        assertFocusedHas("Size", "100%")
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        assertFocusedHas("Opacity", "100%")
        press(Key.DirectionLeft)
        press(Key.DirectionDown)
        assertFocusedHas("Vibration")
        rule.onNodeWithText("A short tick on each press.").assertExists()
        press(Key.Enter)

        assertEquals(
            listOf(
                "touch ${TouchSettings(mode = TouchMode.Always)}",
                "touch ${TouchSettings(size = 105)}",
                "touch ${TouchSettings(opacity = 95)}",
                "touch ${TouchSettings(vibration = false)}",
            ),
            calls,
        )
    }

    @Test
    fun touchSectionComesBeforePorts() {
        show(controllers = emptyList())

        // Section titles show in capitals (Eyebrow).
        val touch = rule.onNodeWithText("TOUCH CONTROLS", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val ports = rule.onNodeWithText("PORTS", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(touch.top < ports.top)
    }

    @Test
    fun theMenuTriggersAreShown() {
        show()

        rule.onNodeWithText("Back, the Mode", substring = true).assertExists()
    }

    @Test
    fun opensOnTheFirstControllersPort() {
        show()

        assertFocusedHas("Test Pad", "Port 1")
    }

    @Test
    fun rightPutsTheControllerOnTheNextPort() {
        show()

        press(Key.DirectionRight)

        assertEquals(listOf("port pad-a 1"), calls)
    }

    @Test
    fun rumbleStepsByTen() {
        show()
        press(Key.DirectionDown)
        assertFocusedHas("Rumble strength", "60%")

        press(Key.DirectionRight)

        assertEquals(listOf("rumble pad-a 70"), calls)
    }

    @Test
    fun aControllerWithNoMotorsSaysSo() {
        show(controllers = listOf(PAD.copy(motors = 0)))

        rule.onNodeWithText("Test Pad has no motors Android can drive, so it doesn't rumble.").assertExists()
    }

    // A controller on a pad the core doesn't offer reads as the core would name it: pad 2 is Port 1B, never "Port 3".
    @Test
    fun aPortTheCoreDoesntOfferIsLabelledAsTheCoreWould() {
        show(ports = mapOf(PAD.descriptor to 2), offered = OFFERED)

        assertFocusedHas("Port 1B")
    }

    // Review Focus 1: a controller left on a port nothing offers shows it, with its note.
    @Test
    fun aPortNoGameOffersShowsAsItself() {
        show(ports = mapOf(PAD.descriptor to 3), offered = OFFERED + Choice(3, "Port 1C", "No game uses this port"))

        assertFocusedHas("Port 1C")
        rule.onNodeWithText("No game uses this port").assertExists()
    }

    @Test
    fun aPortsRowSaysItsTypeAndControllersAndOpensIt() {
        show()

        rule.onNodeWithText("Analog Controller (DualShock) · Test Pad").performScrollTo()
        rule.onNodeWithText("Not Connected · No controller").performScrollTo()
        repeat(7) { press(Key.DirectionDown) }
        assertFocusedHas("Port 1", "Analog Controller")
        press(Key.Enter)

        assertEquals(listOf("open 0"), calls)
    }

    @Test
    fun multitapOffersTheCoresModes() {
        show()
        repeat(6) { press(Key.DirectionDown) }
        assertFocusedHas("Multitap", "Disabled")

        press(Key.DirectionRight)

        assertEquals(listOf("multitap Port1Only"), calls)
    }

    @Test
    fun backFromAPortFocusesItsRow() {
        show(cameFrom = ControllersView.Port(1))

        assertFocusedHas("Port 2")
    }

    @Test
    fun backFromTheHotkeysFocusesTheirRow() {
        show(cameFrom = ControllersView.Hotkeys)

        assertFocusedHas("Hotkeys", "Select + a button")
        press(Key.Enter)
        assertEquals(listOf("hotkeys"), calls)
    }

    // The page as the app has it on the Thor, on its hub, with the selected controller's hotkeys read.
    private fun showInPanels() =
        rule.showControllersInPanels(
            ControllersState(
                controllers = listOf(PAD),
                selected = PAD.descriptor,
                bindings = emptyList(),
                hotkeys = HOTKEYS,
                bindingsFor = PAD.descriptor,
                rumble = mapOf(PAD.descriptor to 60),
                offered = OFFERED,
                portTypes = mapOf(0 to "AnalogController", 1 to "None"),
                typeNames = TYPES,
                multitap = MULTITAP,
                multitapNames = MODES,
            ),
            calls,
        )

    // Down the hub to the row with [summary], and A on it.
    private fun openFromTheHub(summary: String) {
        walkTo(summary)
        press(Key.ButtonA)
    }

    // A controller's B, as it comes on a device.
    private fun pressBOnTheDevice() {
        deviceKeys.press(KeyEvent.KEYCODE_BUTTON_B)
        rule.waitForIdle()
    }

    @Test
    fun backFromTheHotkeysInThePanelsFocusesTheirRow() {
        showInPanels()
        openFromTheHub("Select + a button")
        rule.onNodeWithText("General hotkey 1").assertExists()

        pressBOnTheDevice()

        assertFocusedHas("Hotkeys", "Select + a button")
    }

    @Test
    fun backFromAPortInThePanelsFocusesItsRow() {
        showInPanels()
        openFromTheHub("Not Connected")
        assertFocusedHas("Controller type")

        pressBOnTheDevice()

        assertFocusedHas("Port 2", "Not Connected")
    }

    @Test
    fun noControllerSaysToConnectOne() {
        show(controllers = emptyList())

        assertFocusedHas("Connect a controller to set it up.")
        press(Key.ButtonB)
        assertEquals(listOf("back"), calls)
    }

    // The page's one screen picks its view's: the hub, a port's page, a macro and the hotkeys.
    @Test
    fun thePageShowsItsViewsScreen() {
        var state by
            mutableStateOf(
                ControllersState(
                    controllers = listOf(PAD),
                    selected = PAD.descriptor,
                    bindings = emptyList(),
                    hotkeys = emptyList(),
                    macros = emptyList(),
                    bindingsFor = PAD.descriptor,
                    offered = OFFERED,
                ),
            )
        rule.setContent {
            GooseStationTheme {
                ControllersPageScreen(
                    state,
                    FakeControllersActions(calls),
                    onBack = {},
                )
            }
        }

        rule.onNodeWithText("Back, the Mode", substring = true).assertExists()

        state = state.copy(view = ControllersView.Port(1))
        rule.waitForIdle()
        rule.onNodeWithText("No controller is on Port 2", substring = true).assertExists()

        state = state.copy(view = ControllersView.Macro(0, 3))
        rule.waitForIdle()
        rule.onNodeWithText("Macro 3 · Port 1").assertExists()

        state = state.copy(view = ControllersView.Hotkeys)
        rule.waitForIdle()
        rule.onNodeWithText("Hold Select and press the button you set.", substring = true).assertExists()
        rule.onNodeWithText("Back, the Mode", substring = true).assertDoesNotExist()
    }

    private companion object {
        val PAD = ConnectedController(deviceId = 5, name = "Test Pad", descriptor = "pad-a", motors = 2)
        val OFFERED = listOf(Choice(0, "Port 1"), Choice(1, "Port 2"))
        val TYPES = listOf(Choice("None", "Not Connected"), Choice("AnalogController", "Analog Controller (DualShock)"))
        val MODES = listOf(Choice("Disabled", "Disabled"), Choice("Port1Only", "Enable on Port 1 Only"))
        val MULTITAP = pageValues(listOf(PortSettings.MULTITAP))
        val HOTKEYS =
            listOf("General", "System", "Save States", "Controller", "Graphics", "Audio").flatMap { category ->
                (1..4).map { PadBinding("$category$it", "$category hotkey $it", PadBindingKind.Button, null, category) }
            }
    }
}
