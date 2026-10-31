// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The core's on-screen messages, as the app shows them: by the core's key, in the app's words, or not at all. */
class CoreMessagesTest {
    @Test
    fun saveAndLoadMessagesAreTheApps() {
        assertNull(CoreMessages.translate("save_state", "State saved to 'SCUS-94900_3.sav'."))
        assertNull(CoreMessages.translate("load_state", "Loading state from 'SCUS-94900_3.sav'..."))
    }

    @Test
    fun aResetSaysConsoleReset() {
        assertEquals("Console reset", CoreMessages.translate("system_reset", "System reset."))
    }

    @Test
    fun theAnalogModeSaysOnOrOff() {
        assertEquals(
            "Analog mode on",
            CoreMessages.translate("analog_mode_toggle_0", "Controller 1 switched to analog mode."),
        )
        assertEquals(
            "Analog mode off",
            CoreMessages.translate("analog_mode_toggle_0", "Controller 1 switched to digital mode."),
        )
        assertEquals(
            "Analog mode on",
            CoreMessages.translate("Controller0AnalogMode", "Controller 1 switched to analog mode."),
        )
    }

    @Test
    fun aLockedModeSaysTheGameKeepsIt() {
        assertEquals(
            "The game keeps the controller in digital mode.",
            CoreMessages.translate("Controller0AnalogMode", "Controller 1 is locked to digital mode by the game."),
        )
        assertEquals(
            "The game keeps the controller in analog mode.",
            CoreMessages.translate("Controller0AnalogMode", "Controller 1 is locked to analog mode by the game."),
        )
    }

    @Test
    fun aScreenshotSaysOnlyHowItEnded() {
        val key = "ScreenshotSaver_/data/screenshots/x.png"
        assertNull(CoreMessages.translate(key, "Saving screenshot to 'x.png'."))
        assertEquals("Screenshot saved", CoreMessages.translate(key, "Saved screenshot to 'x.png'."))
        assertEquals(
            "Couldn't save the screenshot",
            CoreMessages.translate(key, "Failed to save screenshot to 'x.png'."),
        )
    }

    @Test
    fun otherMessagesShowAsTheyAre() {
        assertEquals("Controller connected.", CoreMessages.translate("", "Controller connected."))
        assertEquals("Memory card saved.", CoreMessages.translate("MemoryCardSave0", "Memory card saved."))
    }

    @Test
    fun theCoresEmojiAreLeftOut() {
        val key = "ScreenshotSaver_/data/screenshots/x.png"
        assertEquals("Screenshot saved", CoreMessages.translate(key, "\uD83D\uDCF7  Saved screenshot to 'x.png'."))
        assertEquals(
            "Couldn't save the screenshot",
            CoreMessages.translate(key, "\uD83D\uDCF7  Failed to save screenshot to 'x.png'."),
        )
        assertNull(CoreMessages.translate(key, "\uD83D\uDCF8  Saving screenshot to 'x.png'."))
        assertEquals(
            "Memory card saved.",
            CoreMessages.translate("MemoryCardSave0", "\uD83D\uDCBE  Memory card saved."),
        )
        assertEquals("Paused \u00B7 2 of 3", CoreMessages.translate("", "\u23F8\uFE0F Paused \u00B7 2 of 3"))
    }
}
