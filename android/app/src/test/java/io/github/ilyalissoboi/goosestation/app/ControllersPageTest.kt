// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.TypeSetting
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.FloatSetting
import io.github.ilyalissoboi.goosestation.settings.MacroSettings
import io.github.ilyalissoboi.goosestation.settings.MemoryCoreSettings
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Controllers page's capture, with a fake core and fake pads that write to one call log. */
class ControllersPageTest {
    private val calls = mutableListOf<String>()
    private val api = FakeCoreApi(calls)
    private val pads = FakePads(calls)
    private val settings = MemoryCoreSettings()

    private fun TestScope.openPage(): ControllersPage {
        val page =
            ControllersPage(backgroundScope, StandardTestDispatcher(testScheduler), api, { pads }, { true }, settings)
        page.open()
        runCurrent()
        assertEquals(PAD_A, page.state.selected)
        return page
    }

    @Test
    fun theControllersPortTypeIsReadWithTheBindings() =
        runTest {
            api.ports = mapOf(PAD_A to 1)
            api.portTypes = mapOf(0 to "AnalogController", 1 to "PlayStationMouse")

            val page = openPage()

            assertEquals("PlayStationMouse", page.state.bindingsType)
        }

    // --- Ports, types, macros and hotkeys ---

    private val deadzone =
        TypeSetting(
            TypeSetting.Kind.Float,
            "AnalogDeadzone",
            "Analog Deadzone",
            "",
            "0.00",
            "0.00",
            "1.00",
            "0.01",
            "%.0f%%",
            100f,
            emptyList(),
        )

    @Test
    fun theHubReadsEachControllersPortAndRumble() =
        runTest {
            api.ports = mapOf(PAD_A to 1)
            api.rumble = mapOf(PAD_A to 40)
            api.names["ControllerType"] = listOf("None", "AnalogController")
            api.names["MultitapMode"] = listOf("Disabled", "Port1Only")

            val page = openPage()

            assertEquals(mapOf(PAD_A to 1), page.state.ports)
            assertEquals(mapOf(PAD_A to 40), page.state.rumble)
            assertEquals(listOf(0, 1), page.state.offered.map { it.value })
            assertEquals("AnalogController", page.state.portTypes[0])
            assertEquals("None", page.state.portTypes[1])
            assertEquals("Disabled", page.state.multitap!![PortSettings.MULTITAP])
            assertEquals(listOf("None", "AnalogController"), page.state.typeNames.map { it.value })
            assertEquals(listOf("Disabled", "Port1Only"), page.state.multitapNames.map { it.value })
        }

    @Test
    fun theNamesStayAcrossAReopen() =
        runTest {
            api.names["ControllerType"] = listOf("None", "AnalogController")
            val page = openPage()

            page.close()
            page.open()
            runCurrent()

            assertEquals(listOf("None", "AnalogController"), page.state.typeNames.map { it.value })
            assertEquals(1, calls.count { it == "getSettingNames ControllerType" })
        }

    // Back to the hub focuses the row the page was opened from.
    @Test
    fun backSaysWhereItCameFrom() =
        runTest {
            val page = openPage()
            page.openPort(1)
            page.openMacro(1, 3)
            runCurrent()

            page.back()
            assertEquals(ControllersView.Macro(1, 3), page.state.cameFrom)
            page.back()
            assertEquals(ControllersView.Port(1), page.state.cameFrom)
        }

    @Test
    fun aPortReadsItsMacrosButtons() =
        runTest {
            settings.values["Pad1/Macro2Binds"] = "Cross & Circle"
            val page = openPage()

            page.openPort(0)
            runCurrent()

            assertEquals("Cross & Circle", page.state.portValues!![MacroSettings.binds(0, 2)])
            assertEquals("", page.state.portValues!![MacroSettings.binds(0, 1)])
        }

    @Test
    fun movingAControllerToPort2ShowsItThere() =
        runTest {
            val page = openPage()

            page.setPort(PAD_A, 1)
            runCurrent()
            assertEquals(1, page.state.ports[PAD_A])
            assertTrue("setControllerPort $PAD_A 1" in calls)

            page.openPort(1)
            runCurrent()
            assertEquals(ControllersView.Port(1), page.state.view)
            assertEquals(PAD_A, page.state.selected)
            assertEquals(PAD_A, page.state.bindingsFor)
        }

    @Test
    fun aPortWithNoControllerHasNoSelection() =
        runTest {
            val page = openPage()

            page.openPort(1)
            runCurrent()

            assertNull(page.state.selected)
            page.capture("Cross")
            runCurrent()
            assertNull(page.state.capturing)
        }

    // Review Focus 1: a controller left on a port nothing offers any more still shows it, noted.
    @Test
    fun aPadNotOfferedStillShows() =
        runTest {
            api.ports = mapOf(PAD_A to 3)

            val page = openPage()

            val port = page.state.offered.single { it.value == 3 }
            assertEquals("Port 1C", port.label)
            assertEquals("No game uses this port", port.note)
        }

    @Test
    fun multitapOnReadsTheOfferedPortsAgain() =
        runTest {
            val page = openPage()
            api.offered = listOf(Choice(0, "Port 1A"), Choice(2, "Port 1B"), Choice(1, "Port 2"))

            page.setMultitap(page.state.multitap!!.with(PortSettings.MULTITAP, "Port1Only"))
            runCurrent()

            assertEquals("Port1Only", settings.values["ControllerPorts/MultitapMode"])
            assertEquals(listOf("Port 1A", "Port 1B", "Port 2"), page.state.offered.map { it.label })
        }

    @Test
    fun aPortReadsItsTypesSettings() =
        runTest {
            api.typeSettings = mapOf("AnalogController" to listOf(deadzone))
            settings.values["Pad1/AnalogDeadzone"] = "0.25"
            val page = openPage()

            page.openPort(0)
            runCurrent()

            assertEquals(listOf(deadzone), page.state.typeSettings)
            assertEquals(0.25f, page.state.portValues!![PortSettings.setting(0, deadzone) as FloatSetting])
            assertEquals("AnalogController", page.state.bindingsType)
        }

    @Test
    fun aTypeChangeReadsItsSettingsAndBindings() =
        runTest {
            api.typeSettings = mapOf("AnalogController" to listOf(deadzone))
            val page = openPage()
            page.openPort(0)
            runCurrent()

            page.setType(0, "NeGcon")
            runCurrent()

            assertTrue("setPortType 0 NeGcon" in calls)
            assertEquals(emptyList<TypeSetting>(), page.state.typeSettings)
            assertEquals("NeGcon", page.state.portTypes[0])
            assertEquals("NeGcon", page.state.bindingsType)
        }

    @Test
    fun aPortChangeWritesOnlyWhatChanged() =
        runTest {
            api.typeSettings = mapOf("AnalogController" to listOf(deadzone))
            val page = openPage()
            page.openPort(0)
            runCurrent()

            val setting = PortSettings.setting(0, deadzone) as FloatSetting
            page.changePort(page.state.portValues!!.with(setting, 0.3f))
            runCurrent()

            assertEquals(mapOf("Pad1/AnalogDeadzone" to "0.3"), settings.values)
        }

    // From review: back from a macro the port reads its values again; a change made before that read came back was
    // saved, then the read put the old value back on the page.
    @Test
    fun aPortChangeWhileItsValuesAreReadAgainKeepsTheChange() =
        runTest {
            api.typeSettings = mapOf("AnalogController" to listOf(deadzone))
            val page = openPage()
            page.openPort(0)
            runCurrent()
            page.openMacro(0, 1)
            runCurrent()

            page.back()
            val setting = PortSettings.setting(0, deadzone) as FloatSetting
            page.changePort(page.state.portValues!!.with(setting, 0.3f))
            runCurrent()

            assertEquals("0.3", settings.values["Pad1/AnalogDeadzone"])
            assertEquals(0.3f, page.state.portValues!![setting])
        }

    @Test
    fun aStaleBindingsReadIsDropped() =
        runTest {
            pads.connected.value =
                listOf(ConnectedController(1, "Pad A", PAD_A), ConnectedController(2, "Pad B", PAD_B))
            api.ports = mapOf(PAD_B to 1)
            val page = openPage()

            page.openPort(0)
            page.openPort(1)
            runCurrent()

            assertEquals(PAD_B, page.state.selected)
            assertEquals(PAD_B, page.state.bindingsFor)
        }

    @Test
    fun backGoesUpOneLevel() =
        runTest {
            val page = openPage()
            page.openPort(0)
            page.openMacro(0, 2)
            runCurrent()
            assertEquals(ControllersView.Macro(0, 2), page.state.view)

            assertTrue(page.back())
            assertEquals(ControllersView.Port(0), page.state.view)
            assertTrue(page.back())
            assertEquals(ControllersView.Hub, page.state.view)
            assertFalse(page.back())
        }

    @Test
    fun aMacroReadsItsSettingsInputsAndTrigger() =
        runTest {
            settings.values["Pad1/Macro2Binds"] = "Cross & Circle"
            api.macros["Macro2"] = "Button99"
            val page = openPage()

            page.openMacro(0, 2)
            runCurrent()

            assertEquals("Cross & Circle", page.state.macroValues!![MacroSettings.binds(0, 2)])
            assertEquals(
                "Button99",
                page.state.macros!!
                    .single { it.name == "Macro2" }
                    .binding,
            )
            assertEquals(listOf("Cross", "Circle"), page.state.macroInputs.map { it.name })
        }

    @Test
    fun aMacroTriggerCaptures() =
        runTest {
            val page = openPage()
            page.openMacro(0, 1)
            runCurrent()

            page.capture("Macro1")
            runCurrent()
            page.onBindingCaptured("Button99")
            runCurrent()

            assertTrue(calls.toString(), "setPadBinding $PAD_A Macro1 Button99" in calls)
            assertEquals(
                "Button99",
                page.state.macros!!
                    .single { it.name == "Macro1" }
                    .binding,
            )
        }

    @Test
    fun hotkeysCoverEveryController() =
        runTest {
            pads.connected.value =
                listOf(ConnectedController(1, "Pad A", PAD_A), ConnectedController(2, "Pad B", PAD_B))
            api.ports = mapOf(PAD_B to 1)
            val page = openPage()
            page.openPort(0)
            runCurrent()

            page.openHotkeys()
            page.select(PAD_B)
            runCurrent()

            assertEquals(ControllersView.Hotkeys, page.state.view)
            assertEquals(PAD_B, page.state.selected)
        }

    // After a move, the controller's bindings are its new port's type's, read again.
    @Test
    fun aMoveReadsTheBindingsOfTheNewPortsType() =
        runTest {
            api.portTypes = mapOf(0 to "AnalogController", 1 to "NeGcon")
            val page = openPage()
            assertEquals("AnalogController", page.state.bindingsType)

            page.setPort(PAD_A, 1)
            runCurrent()

            assertEquals("NeGcon", page.state.bindingsType)
        }

    // Two hub reads close together (a controller connecting as the page opens) still give the core's names.
    @Test
    fun theNamesSurviveTwoReadsAtOnce() =
        runTest {
            api.names["ControllerType"] = listOf("None", "AnalogController")
            val page =
                ControllersPage(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    api,
                    { pads },
                    { true },
                    settings,
                )
            page.open()
            pads.connected.value =
                listOf(ConnectedController(1, "Pad A", PAD_A), ConnectedController(2, "Pad B", PAD_B))
            runCurrent()

            assertEquals(listOf("None", "AnalogController"), page.state.typeNames.map { it.value })
        }

    // The ports offered keep the core's order, a port's slots together; a lost one goes in its place.
    @Test
    fun theOfferedOrderIsTheCores() =
        runTest {
            api.offered = listOf(Choice(0, "Port 1A"), Choice(2, "Port 1B"), Choice(1, "Port 2"))
            api.ports = mapOf(PAD_A to 6)

            val page = openPage()

            assertEquals(listOf(0, 2, 1, 6), page.state.offered.map { it.value })
        }

    // Another port's page never shows with the last port's values, whose settings it doesn't have.
    @Test
    fun anotherPortStartsWithNoValues() =
        runTest {
            api.typeSettings = mapOf("AnalogController" to listOf(deadzone))
            val page = openPage()
            page.openPort(0)
            runCurrent()
            assertNotNull(page.state.portValues)
            page.back()

            page.openPort(1)

            assertNull(page.state.portValues)
            assertEquals(emptyList<TypeSetting>(), page.state.typeSettings)
        }

    @Test
    fun rumbleIsSetPerController() =
        runTest {
            val page = openPage()

            page.setRumble(PAD_A, 30)
            runCurrent()

            assertEquals(30, page.state.rumble[PAD_A])
            assertTrue("setRumbleStrength $PAD_A 30" in calls)
        }

    @Test
    fun aResultAfterACancelIsDropped() =
        runTest {
            val page = openPage()
            page.capture("Cross")
            runCurrent()

            page.cancelCapture()
            runCurrent()
            page.onBindingCaptured("Button99")
            runCurrent()

            assertTrue(calls.none { it.startsWith("setPadBinding") })
            assertNull(page.state.capturing)
            assertEquals(
                "Button96",
                page.state.bindings!!
                    .single { it.name == "Cross" }
                    .binding,
            )
        }

    @Test
    fun theTimeoutCancelsOnlyItsOwnCapture() =
        runTest {
            val page = openPage()
            page.capture("Cross")
            runCurrent()
            advanceTimeBy(2_000)
            page.cancelCapture()
            page.capture("Circle")
            runCurrent()

            // Past Cross's 5 s, and 3.5 s into Circle's.
            advanceTimeBy(3_500)
            runCurrent()

            assertEquals("Circle", page.state.capturing)
            assertEquals(2, page.state.secondsLeft)
        }

    @Test
    fun everyEndReleasesAfterTheNativeCancel() =
        runTest {
            val page = openPage()

            page.capture("Cross")
            runCurrent()
            page.onBindingCaptured("Button99")
            runCurrent()
            assertReleasedAfterTheCancel()

            calls.clear()
            page.capture("Cross")
            runCurrent()
            page.cancelCapture()
            runCurrent()
            assertReleasedAfterTheCancel()

            calls.clear()
            page.capture("Cross")
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()
            assertNull(page.state.capturing)
            assertReleasedAfterTheCancel()
        }

    @Test
    fun axesAreSentBeforeTheStart() =
        runTest {
            val page = openPage()

            page.capture("Cross")
            runCurrent()

            val sent = calls.indexOf("sendAxes $PAD_A")
            assertTrue(sent >= 0)
            assertTrue(sent < calls.indexOf("startBindingCapture $PAD_A"))
        }

    @Test
    fun aGoneControllerCancelsItsCapture() =
        runTest {
            val page = openPage()
            page.capture("Cross")
            runCurrent()
            calls.clear()

            pads.connected.value = emptyList()
            runCurrent()

            assertNull(page.state.capturing)
            assertEquals(listOf("cancelBindingCapture", "releaseAll"), calls)
        }

    @Test
    fun hotkeysAreReadWithTheBindings() =
        runTest {
            val page = openPage()

            assertEquals(
                listOf("ToggleFastForward", "ToggleAnalogMode", "Screenshot"),
                page.state.hotkeys!!.map { it.name },
            )
        }

    @Test
    fun aHotkeyCaptureSetsTheHotkey() =
        runTest {
            val page = openPage()
            calls.clear()

            page.captureHotkey("ToggleFastForward")
            runCurrent()
            assertEquals("ToggleFastForward", page.state.capturing)
            page.onBindingCaptured("Button99")
            runCurrent()

            assertTrue(calls.contains("startBindingCapture $PAD_A chord"))
            assertTrue(calls.contains("setHotkeyBinding $PAD_A ToggleFastForward Button99"))
            assertTrue(calls.none { it.startsWith("setPadBinding") })
            assertEquals(
                "Button109 & Android-0/Button99",
                page.state.hotkeys!!
                    .single { it.name == "ToggleFastForward" }
                    .binding,
            )
        }

    @Test
    fun aPadCaptureIsntAChord() =
        runTest {
            val page = openPage()
            calls.clear()

            page.capture("Cross")
            runCurrent()

            assertTrue(calls.contains("startBindingCapture $PAD_A"))
        }

    @Test
    fun clearAndUndoWorkOnHotkeys() =
        runTest {
            val page = openPage()
            page.captureHotkey("Screenshot")
            runCurrent()
            page.onBindingCaptured("Button99")
            runCurrent()
            page.captureHotkey("Screenshot")
            runCurrent()
            page.onBindingCaptured("Button100")
            runCurrent()
            assertEquals(Undo("Screenshot", "Button109 & Android-0/Button99"), page.state.undo)
            calls.clear()

            page.undo()
            runCurrent()
            assertEquals(listOf("setHotkeyBinding $PAD_A Screenshot Button99"), calls)

            calls.clear()
            page.clear("Screenshot")
            runCurrent()
            assertEquals(listOf("setHotkeyBinding $PAD_A Screenshot null"), calls)
            assertNull(
                page.state.hotkeys!!
                    .single { it.name == "Screenshot" }
                    .binding,
            )
        }

    @Test
    fun undoPutsThePreviousBindingBack() =
        runTest {
            val page = openPage()
            page.capture("Cross")
            runCurrent()
            page.onBindingCaptured("Button100")
            runCurrent()
            assertEquals(Undo("Cross", "Button96"), page.state.undo)
            calls.clear()

            page.undo()
            runCurrent()

            assertEquals(listOf("setPadBinding $PAD_A Cross Button96"), calls)
            assertNull(page.state.undo)
            assertEquals(
                "Button96",
                page.state.bindings!!
                    .single { it.name == "Cross" }
                    .binding,
            )
        }

    @Test
    fun anotherCaptureDropsTheUndo() =
        runTest {
            val page = openPage()
            page.capture("Cross")
            runCurrent()
            page.onBindingCaptured("Button100")
            runCurrent()

            page.capture("Circle")

            assertNull(page.state.undo)
        }

    @Test
    fun dropUndoForgetsIt() =
        runTest {
            val page = openPage()
            page.capture("Cross")
            runCurrent()
            page.onBindingCaptured("Button100")
            runCurrent()

            page.dropUndo()
            page.undo()
            runCurrent()

            assertNull(page.state.undo)
            assertTrue(calls.none { it == "setPadBinding $PAD_A Cross Button96" })
        }

    // Review Focus 2: by their places in the descriptor table, so the numbers stay the same from one run to the next.
    @Test
    fun sameNamesAreNumbered() =
        runTest {
            api.indices = mapOf(PAD_A to 1, "pad-b" to 0, "pad-c" to 2)
            pads.connected.value =
                listOf(
                    ConnectedController(1, "Wireless Controller", PAD_A),
                    ConnectedController(2, "Wireless Controller", "pad-b"),
                    ConnectedController(3, "Pad C", "pad-c"),
                )
            val page = openPage()

            assertEquals(
                mapOf(PAD_A to "Wireless Controller (2)", "pad-b" to "Wireless Controller (1)", "pad-c" to "Pad C"),
                page.state.labels,
            )

            pads.connected.value = listOf(ConnectedController(1, "Wireless Controller", PAD_A))
            runCurrent()

            assertEquals(mapOf(PAD_A to "Wireless Controller"), page.state.labels)
        }

    // A brand-new pad's place can be asked for before the core has added it: it's asked for once more.
    @Test
    fun aNewPadIsNumberedOnceTheTableHasIt() =
        runTest {
            api.indices = mapOf(PAD_A to 0, "pad-b" to 1)
            api.notYetInTable += "pad-b"
            pads.connected.value =
                listOf(
                    ConnectedController(1, "Wireless Controller", PAD_A),
                    ConnectedController(2, "Wireless Controller", "pad-b"),
                )
            val page = openPage()

            assertEquals(
                mapOf(PAD_A to "Wireless Controller (1)", "pad-b" to "Wireless Controller (2)"),
                page.state.labels,
            )
        }

    // A pad the table never has keeps its bare name, and isn't asked for over and over.
    @Test
    fun aPadTheTableLacksIsAskedForTwice() =
        runTest {
            api.indices = mapOf(PAD_A to 0)
            pads.connected.value =
                listOf(
                    ConnectedController(1, "Wireless Controller", PAD_A),
                    ConnectedController(2, "Wireless Controller", "pad-b"),
                )
            val page = openPage()

            assertEquals("Wireless Controller", page.state.labels["pad-b"])
            assertEquals(2, calls.count { it == "getControllerIndex pad-b" })
        }

    // T7-3: until the new pad's bindings are read, the page knows the ones it has are another's, and edits nothing.
    @Test
    fun aSwitchSaysWhoseBindingsAreShown() =
        runTest {
            pads.connected.value =
                listOf(ConnectedController(1, "Pad A", PAD_A), ConnectedController(2, "Pad B", "pad-b"))
            val page = openPage()
            assertEquals(PAD_A, page.state.bindingsFor)

            page.select("pad-b")
            page.capture("Cross")
            page.clear("Cross")

            assertEquals(PAD_A, page.state.bindingsFor)
            assertNull(page.state.capturing)
            runCurrent()
            assertEquals("pad-b", page.state.bindingsFor)
            assertTrue(calls.none { it.startsWith("setPadBinding") || it.startsWith("startBindingCapture") })
        }

    // Review finding: the pad picked earlier coming back mustn't take the page from the one in use, mid-capture.
    @Test
    fun aPickedPadReconnectingKeepsThePadInUse() =
        runTest {
            val padA = ConnectedController(1, "Pad A", PAD_A)
            val padB = ConnectedController(2, "Pad B", "pad-b")
            pads.connected.value = listOf(padA, padB)
            val page = openPage()
            page.select("pad-b")
            runCurrent()
            pads.connected.value = listOf(padA)
            runCurrent()
            assertEquals(PAD_A, page.state.selected)
            page.capture("Cross")
            runCurrent()

            pads.connected.value = listOf(padA, padB)
            runCurrent()

            assertEquals(PAD_A, page.state.selected)
            assertEquals("Cross", page.state.capturing)
        }

    // On opening, the pad picked last is selected again, if it's connected.
    @Test
    fun theOpeningSelectsThePadPickedLast() =
        runTest {
            pads.connected.value =
                listOf(ConnectedController(1, "Pad A", PAD_A), ConnectedController(2, "Pad B", "pad-b"))
            val page = openPage()
            page.select("pad-b")
            runCurrent()

            page.close()
            page.open()
            runCurrent()

            assertEquals("pad-b", page.state.selected)
        }

    // After the start, the capture's last cancelBindingCapture, if any, comes before a releaseAll.
    private fun assertReleasedAfterTheCancel() {
        val started = calls.indexOf("startBindingCapture $PAD_A")
        val released = calls.lastIndexOf("releaseAll")
        assertTrue("$calls", started >= 0 && released > started)
        assertTrue("$calls", released > calls.lastIndexOf("cancelBindingCapture"))
    }

    private class FakePads(
        private val calls: MutableList<String>,
    ) : PadPort {
        override val connected = MutableStateFlow(listOf(ConnectedController(1, "Pad A", PAD_A)))

        override fun sendAxes(descriptor: String) {
            calls += "sendAxes $descriptor"
        }

        override fun releaseAll() {
            calls += "releaseAll"
        }
    }

    private companion object {
        const val PAD_A = "pad-a"
        const val PAD_B = "pad-b"
    }
}
