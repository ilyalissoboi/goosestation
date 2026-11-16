// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.os.Build
import android.view.KeyEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.app.ControllersView
import io.github.ilyalissoboi.goosestation.app.TouchMode
import io.github.ilyalissoboi.goosestation.app.TouchSettings
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.After
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

    private val calls = mutableListOf<String>()
    private var leftTouchMode = false

    private fun show(
        controllers: List<ConnectedController> = listOf(PAD),
        ports: Map<String, Int> = emptyMap(),
        offered: List<Choice<Int>> = OFFERED,
        cameFrom: ControllersView? = null,
    ) {
        rule.setContent {
            GooseStationTheme {
                ControllersScreen(
                    controllers = controllers,
                    labels = emptyMap(),
                    ports = ports,
                    rumble = mapOf(PAD.descriptor to 60),
                    offered = offered,
                    portTypes = mapOf(0 to "AnalogController", 1 to "None"),
                    typeNames = TYPES,
                    multitap = MULTITAP,
                    multitapNames = MODES,
                    onPort = { d, pad -> calls += "port $d $pad" },
                    onRumble = { d, percent -> calls += "rumble $d $percent" },
                    onMultitap = { calls += "multitap ${it[PortSettings.MULTITAP]}" },
                    onOpenPort = { calls += "open $it" },
                    onOpenHotkeys = { calls += "hotkeys" },
                    onBack = { calls += "back" },
                    cameFrom = cameFrom,
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

    // The hub, a port's page and the Hotkeys as the app has them: in the settings' two panels, at the Thor's window
    // size, one view at a time, with Back from a port or the Hotkeys to the hub. A port's page here is a row that
    // takes the focus as it opens, as PortScreen's does.
    private fun showInPanels() {
        var view by mutableStateOf<ControllersView>(ControllersView.Hub)
        var cameFrom by mutableStateOf<ControllersView?>(null)
        var inPage by mutableStateOf(true)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(832.dp, 420.dp))) {
                GooseStationTheme {
                    SettingsPanels(
                        current = SettingsEntry.Controllers,
                        inPage = inPage,
                        coreReady = true,
                        locked = false,
                        onShow = { inPage = false },
                        onEnterPage = { inPage = true },
                        onBack = { calls += "leave" },
                    ) { onBack ->
                        val back = {
                            if (view == ControllersView.Hub) {
                                onBack()
                            } else {
                                cameFrom = view
                                view = ControllersView.Hub
                            }
                        }
                        when (val shown = view) {
                            ControllersView.Hotkeys ->
                                HotkeysScreen(
                                    controllers = listOf(PAD),
                                    labels = emptyMap(),
                                    selected = PAD.descriptor,
                                    hotkeys = HOTKEYS,
                                    hotkeysFor = PAD.descriptor,
                                    capturing = null,
                                    secondsLeft = 5,
                                    undoFor = null,
                                    onSelect = {},
                                    onCaptureHotkey = {},
                                    onCancelCapture = {},
                                    onClear = {},
                                    onUndo = {},
                                    onDropUndo = {},
                                    onBack = back,
                                )
                            is ControllersView.Port -> {
                                val first = remember { FocusRequester() }
                                LaunchedEffect(Unit) { first.requestFocus() }
                                SettingsPage("Port ${shown.pad + 1}", onBack = back) {
                                    SwitchRow("Port's first row", false, onToggle = {}, Modifier.focusRequester(first))
                                }
                            }
                            else ->
                                ControllersScreen(
                                    controllers = listOf(PAD),
                                    labels = emptyMap(),
                                    ports = emptyMap(),
                                    rumble = mapOf(PAD.descriptor to 60),
                                    offered = OFFERED,
                                    portTypes = mapOf(0 to "AnalogController", 1 to "None"),
                                    typeNames = TYPES,
                                    multitap = MULTITAP,
                                    multitapNames = MODES,
                                    onPort = { _, _ -> },
                                    onRumble = { _, _ -> },
                                    onMultitap = {},
                                    onOpenPort = { view = ControllersView.Port(it) },
                                    onOpenHotkeys = { view = ControllersView.Hotkeys },
                                    onBack = back,
                                    cameFrom = cameFrom,
                                )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    // Down the hub to the row with [summary], and A on it.
    private fun openFromTheHub(summary: String) {
        var downs = 0
        while (!focusedHas(summary) && downs++ < 20) press(Key.DirectionDown)
        press(Key.ButtonA)
    }

    // A controller's B, as it comes on a device: out of touch mode, through the window. Out of touch mode, a focused
    // row that goes leaves the focus to Android, which gives it to the first thing that takes it: the rail.
    private fun pressBOnTheDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.setInTouchMode(false)
            leftTouchMode = true
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_B)
        rule.waitForIdle()
    }

    // Touch mode is the display's, not the test's: back as the other tests have it.
    @After
    fun backToTouchMode() {
        if (leftTouchMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        }
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
        assertFocusedHas("Port's first row")

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
