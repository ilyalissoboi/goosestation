// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import org.junit.Assert.assertEquals
import org.junit.Test

class BindingLabelsTest {
    private fun assertLabels(vararg expected: Pair<String?, String>) {
        expected.forEach { (binding, label) -> assertEquals("for $binding", label, BindingLabels.label(binding)) }
    }

    @Test
    fun noBindingIsNotSet() {
        assertLabels(null to "Not set", "" to "Not set")
    }

    @Test
    fun faceButtons() {
        assertLabels(
            "Button96" to "A",
            "Button97" to "B",
            "Button98" to "C",
            "Button99" to "X",
            "Button100" to "Y",
            "Button101" to "Z",
        )
    }

    @Test
    fun shouldersSticksAndMenuButtons() {
        assertLabels(
            "Button102" to "L1",
            "Button103" to "R1",
            "Button104" to "L2",
            "Button105" to "R2",
            "Button106" to "L3",
            "Button107" to "R3",
            "Button108" to "Start",
            "Button109" to "Select",
            "Button110" to "Mode",
        )
    }

    @Test
    fun dpadKeys() {
        assertLabels(
            "Button19" to "D-pad up",
            "Button20" to "D-pad down",
            "Button21" to "D-pad left",
            "Button22" to "D-pad right",
        )
    }

    @Test
    fun numberedButtons() {
        assertLabels("Button188" to "Button 1", "Button190" to "Button 3", "Button203" to "Button 16")
    }

    @Test
    fun anyOtherKey() {
        assertLabels(
            "Button250" to "Key 250",
            "Button23" to "Key 23",
            "Button187" to "Key 187",
            "Button204" to "Key 204",
        )
    }

    @Test
    fun leftStick() {
        assertLabels(
            "+Axis0" to "Left stick right",
            "-Axis0" to "Left stick left",
            "+Axis1" to "Left stick down",
            "-Axis1" to "Left stick up",
        )
    }

    @Test
    fun rightStick() {
        assertLabels(
            "+Axis11" to "Right stick right",
            "-Axis11" to "Right stick left",
            "+Axis14" to "Right stick down",
            "-Axis14" to "Right stick up",
        )
    }

    @Test
    fun triggers() {
        assertLabels(
            "+Axis17" to "Left trigger",
            "+Axis18" to "Right trigger",
            "+Axis23" to "Brake (left trigger)",
            "+Axis22" to "Gas (right trigger)",
        )
    }

    @Test
    fun hatIsTheDpad() {
        assertLabels(
            "+Axis15" to "D-pad right",
            "-Axis15" to "D-pad left",
            "+Axis16" to "D-pad down",
            "-Axis16" to "D-pad up",
        )
    }

    @Test
    fun anyOtherAxis() {
        assertLabels("+Axis32" to "Axis 32 +", "-Axis32" to "Axis 32 −", "-Axis17" to "Axis 17 −")
    }

    @Test
    fun aChordIsLabelledPartByPart() {
        assertLabels(
            "Button108 & Button109" to "Start + Select",
            // The core strips only the first part's device prefix.
            "Button108 & Android-0/Button109" to "Start + Select",
            "Android-0/Button108 & Android-0/Button109" to "Start + Select",
        )
    }
}
