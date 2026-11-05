// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.core.TypeSetting
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortSettingsTest {
    // As AnalogController's: 0 to 100% by 1%, shown through its format.
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
    private val invert =
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
    private val force =
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
    private val bias =
        TypeSetting(
            TypeSetting.Kind.Int,
            "VibrationBias",
            "Vibration Bias",
            "",
            "8",
            "0",
            "255",
            "1",
            "%d",
            1f,
            emptyList(),
        )

    @Test
    fun eachSettingIsInThePortsSection() {
        assertEquals(
            listOf(
                FloatSetting("Pad2", "AnalogDeadzone", 0f),
                IntSetting("Pad2", "InvertLeftStick", 0),
                BoolSetting("Pad2", "ForceAnalogOnReset", true),
                IntSetting("Pad2", "VibrationBias", 8),
            ),
            PortSettings.settings(1, listOf(deadzone, invert, force, bias)),
        )
    }

    @Test
    fun aFloatStepsByItsStepAndShowsThroughItsFormat() {
        val scale = PortSettings.scale(deadzone)!!
        assertEquals("25%", scale.label(25))
        assertEquals(26, scale.next(25, 1))
        assertNull(scale.next(100, 1))
        assertEquals(25, PortSettings.units(deadzone, 0.25f))
        assertEquals(0.25f, PortSettings.value(deadzone, 25), 1e-6f)
    }

    // Review Focus: a desktop value off the steps shows as itself.
    @Test
    fun aFloatOffTheStepsShowsAsItself() {
        assertEquals(33, PortSettings.units(deadzone, 0.333f))
        assertEquals("33%", PortSettings.scale(deadzone)!!.label(33))
    }

    @Test
    fun anIntUsesItsFormat() {
        assertEquals("8", PortSettings.scale(bias)!!.label(8))
        assertEquals(9, PortSettings.scale(bias)!!.next(8, 1))
    }

    @Test
    fun anIntListStoresMinPlusIndex() {
        assertEquals(
            listOf(
                Choice(0, "Not Inverted"),
                Choice(1, "Invert Left/Right"),
                Choice(2, "Invert Up/Down"),
                Choice(3, "Invert Left/Right + Up/Down"),
            ),
            PortSettings.choices(invert),
        )
        assertEquals(
            listOf(Choice(5, "a"), Choice(6, "b")),
            PortSettings.choices(invert.copy(min = "5", options = listOf("a", "b"))),
        )
    }

    @Test
    fun noScaleForABool() {
        assertNull(PortSettings.scale(force))
    }

    @Test
    fun theBridgesFlatListParses() {
        val flat =
            arrayOf(
                "Float",
                "AnalogDeadzone",
                "Analog Deadzone",
                "Desc",
                "0.00",
                "0.00",
                "1.00",
                "0.01",
                "%.0f%%",
                "100",
                "",
            ) +
                arrayOf(
                    "IntList",
                    "InvertLeftStick",
                    "Invert Left Stick",
                    "",
                    "0",
                    "0",
                    "3",
                    "1",
                    "",
                    "1",
                    "Not Inverted\nInvert Left/Right",
                )
        val parsed = TypeSetting.parse(flat)
        assertEquals(2, parsed.size)
        assertEquals(deadzone.copy(description = "Desc"), parsed[0])
        assertEquals(listOf("Not Inverted", "Invert Left/Right"), parsed[1].options)
    }
}
