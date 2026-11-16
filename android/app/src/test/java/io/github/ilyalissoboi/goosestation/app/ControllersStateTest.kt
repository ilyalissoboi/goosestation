// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.Pads
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Controllers page's screens read from its state: the controllers a view covers, names, glyphs and focus. */
class ControllersStateTest {
    private val a = ConnectedController(1, "Pad", "a")
    private val b = ConnectedController(2, "Pad", "b")
    private val c = ConnectedController(3, "Stick", "c")

    // [a] on pad 0, [b] on pad 1, [c] with its port not read yet.
    private val state = ControllersState(controllers = listOf(a, b, c), ports = mapOf("a" to 0, "b" to 1))

    @Test
    fun theHubAndTheHotkeysCoverEveryController() {
        assertEquals(listOf(a, b, c), state.covered)
        assertEquals(listOf(a, b, c), state.copy(view = ControllersView.Hotkeys).covered)
    }

    @Test
    fun aPortAndItsMacrosCoverTheControllersOnItsPad() {
        assertEquals(listOf(b), state.copy(view = ControllersView.Port(1)).covered)
        assertEquals(listOf(b), state.copy(view = ControllersView.Macro(1, 2)).covered)
        // One whose port isn't read yet counts as on pad 0.
        assertEquals(listOf(a, c), state.copy(view = ControllersView.Port(0)).covered)
        assertEquals(emptyList<ConnectedController>(), state.copy(view = ControllersView.Port(2)).covered)
    }

    @Test
    fun coveredAmongAppliesTheViewToAnotherList() {
        val port1 = state.copy(view = ControllersView.Port(1))

        assertEquals(listOf(b), port1.coveredAmong(listOf(c, b)))
        assertEquals(listOf(c, b), state.coveredAmong(listOf(c, b)))
    }

    @Test
    fun theControllerShownIsTheSelectedOneItCoversOrElseTheFirst() {
        assertEquals(b, state.copy(selected = "b").controller)
        assertEquals(a, state.copy(selected = null).controller)
        // [b] is on pad 1: pad 0's page shows its first instead.
        assertEquals(a, state.copy(view = ControllersView.Port(0), selected = "b").controller)
        assertEquals(c, state.copy(view = ControllersView.Port(0), selected = "c").controller)
    }

    @Test
    fun noControllerIsShownWhereNoneIsCovered() {
        assertNull(ControllersState().controller)
        assertNull(state.copy(view = ControllersView.Port(2), selected = "a").controller)
    }

    @Test
    fun aControllerIsNamedByItsLabelOrElseItsName() {
        val labelled = state.copy(labels = mapOf("a" to "Pad (1)", "b" to "Pad (2)"))

        assertEquals("Pad (1)", labelled.label(a))
        assertEquals("Pad (2)", labelled.label(b))
        assertEquals("Stick", labelled.label(c))
    }

    @Test
    fun aPortIsNamedAsTheOfferedPortsNameItOrElseByItsOwnName() {
        val offered = state.copy(offered = listOf(Choice(0, "Port 1"), Choice(2, "Port 1B", "Multitap")))

        assertEquals("Port 1B", offered.portLabel(2))
        // A pad no port offered names, as when a controller is on a port no multitap offers any more.
        assertEquals(Pads.label(5), offered.portLabel(5))
        assertEquals(Pads.label(0), ControllersState().portLabel(0))
    }

    @Test
    fun theDigitalAndAnalogTypesShowGlyphsAndNoOtherDoes() {
        assertTrue(state.copy(bindingsType = "DigitalController").glyphs)
        assertTrue(state.copy(bindingsType = "AnalogController").glyphs)
        assertFalse(state.copy(bindingsType = "PlayStationMouse").glyphs)
        // Not read yet.
        assertFalse(state.glyphs)
    }

    @Test
    fun undoShowsOnTheInputTheLastCaptureReplaced() {
        assertEquals("Cross", state.copy(undo = Undo("Cross", "Button96")).undoFor)
        assertEquals("Circle", state.copy(undo = Undo("Circle", null)).undoFor)
        assertNull(state.undoFor)
    }

    @Test
    fun aPortsPageFocusesTheMacroItCameBackFromOnly() {
        assertEquals(3, state.copy(view = ControllersView.Port(1), cameFrom = ControllersView.Macro(1, 3)).macroFocus)
        assertNull(state.copy(view = ControllersView.Port(1), cameFrom = ControllersView.Hub).macroFocus)
        assertNull(state.copy(view = ControllersView.Port(1), cameFrom = ControllersView.Port(1)).macroFocus)
        // On opening.
        assertNull(state.copy(view = ControllersView.Port(1)).macroFocus)
    }
}
