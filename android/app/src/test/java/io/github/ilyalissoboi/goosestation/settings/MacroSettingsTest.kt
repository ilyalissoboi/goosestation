// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MacroSettingsTest {
    private val inputs =
        listOf("Up", "Cross", "Circle", "L2").map { PadBinding(it, it, PadBindingKind.Button, null) }

    @Test
    fun eachMacroIsInThePortsSection() {
        assertEquals(
            listOf(
                StringSetting("Pad2", "Macro3Binds", "", deleteEmpty = true),
                IntSetting("Pad2", "Macro3Frequency", 0),
                BoolSetting("Pad2", "Macro3Toggle", false),
            ),
            MacroSettings.settings(1, 3),
        )
    }

    @Test
    fun bindsAreJoinedAsDesktopWritesThem() {
        assertEquals("Cross & Circle", MacroSettings.binds(listOf("Circle", "Cross"), inputs))
    }

    @Test
    fun bindsAreReadAsTheCoreSplitsThem() {
        assertEquals(listOf("Cross", "Circle"), MacroSettings.buttons("Cross&Circle "))
        assertEquals(listOf("Cross", "Circle"), MacroSettings.buttons(" Cross & & Circle"))
        assertEquals(emptyList<String>(), MacroSettings.buttons(""))
    }

    // A button the type doesn't have (from another type's macro) is kept, after the type's own.
    @Test
    fun anUnknownButtonIsKept() {
        assertEquals("Cross & Steering", MacroSettings.binds(listOf("Steering", "Cross"), inputs))
    }

    // Desktop clears the key rather than writing it empty.
    @Test
    fun noButtonsDeletesTheKey() {
        val s = MemoryCoreSettings()
        val binds = MacroSettings.binds(0, 1)
        binds.write(s, "Cross")
        assertEquals("Cross", s.values["Pad1/Macro1Binds"])

        binds.write(s, "")

        assertFalse("Pad1/Macro1Binds" in s.values)
    }

    @Test
    fun theFrequencyRowSaysWhatItDoes() {
        assertEquals("Doesn't repeat", MacroSettings.FREQUENCY.label(0))
        assertEquals("Every 4 frames", MacroSettings.FREQUENCY.label(4))
        assertEquals("Every frame", MacroSettings.FREQUENCY.label(1))
        assertEquals(1, MacroSettings.FREQUENCY.next(0, 1))
    }
}
