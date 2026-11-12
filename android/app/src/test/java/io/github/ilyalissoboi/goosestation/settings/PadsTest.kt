// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The multitap pads' order and labels ([Pads]), as `Controller::ConvertPadToPortAndSlot` lays them out. */
class PadsTest {
    @Test
    fun orderKeepsAPortsSlotsTogether() {
        assertEquals(listOf(0, 2, 3, 4, 1, 5, 6, 7), Pads.ORDER)
    }

    @Test
    fun eachModeOffersItsPads() {
        assertEquals(listOf(0, 1), Pads.of("Disabled"))
        assertEquals(listOf(0, 2, 3, 4, 1), Pads.of("Port1Only"))
        assertEquals(listOf(0, 1, 5, 6, 7), Pads.of("Port2Only"))
        assertEquals(Pads.ORDER, Pads.of("BothPorts"))
        assertEquals(listOf(0, 1), Pads.of("Nonsense"))
    }

    // Only the pads each mode offers: a label for one it doesn't is asked for nowhere.
    @Test
    fun aTappedPortsPadsAreLettered() {
        val both = Pads.of("BothPorts").map { Pads.label(it, "BothPorts") }
        assertEquals(
            listOf("Port 1A", "Port 1B", "Port 1C", "Port 1D", "Port 2A", "Port 2B", "Port 2C", "Port 2D"),
            both,
        )
        assertEquals(
            listOf("Port 1A", "Port 1B", "Port 1C", "Port 1D", "Port 2"),
            Pads.of("Port1Only").map { Pads.label(it, "Port1Only") },
        )
        assertEquals(
            listOf("Port 1", "Port 2A", "Port 2B", "Port 2C", "Port 2D"),
            Pads.of("Port2Only").map { Pads.label(it, "Port2Only") },
        )
        assertEquals(listOf("Port 1", "Port 2"), Pads.of("Disabled").map { Pads.label(it, "Disabled") })
    }

    @Test
    fun withoutTheModePortsOneAndTwoAreUnlettered() {
        assertEquals(
            listOf("Port 1", "Port 2", "Port 1B", "Port 1C", "Port 1D", "Port 2B", "Port 2C", "Port 2D"),
            (0..7).map(Pads::label),
        )
    }
}
