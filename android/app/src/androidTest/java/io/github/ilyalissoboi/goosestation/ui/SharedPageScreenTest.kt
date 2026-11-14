// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.PageExtras
import io.github.ilyalissoboi.goosestation.app.SharedPage
import io.github.ilyalissoboi.goosestation.settings.AdvancedSettings
import io.github.ilyalissoboi.goosestation.settings.AudioSettings
import io.github.ilyalissoboi.goosestation.settings.ConsoleSettings
import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.RewindUsage
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The pages both settings owners have ([SharedPageScreen]): each draws its own screen, with the extras it shows. */
@RunWith(AndroidJUnit4::class)
class SharedPageScreenTest {
    // The v2 rule (junit4.v2) runs effects differently: moving to it is a change of its own.
    @Suppress("DEPRECATION")
    @get:Rule
    val rule = createComposeRule()

    private val extras =
        object : PageExtras {
            override val names: CoreNames = SampleNames.ALL
            override val sharedCards = emptyList<String>()
            override val vulkanFailed = true
            override val fastmemAvailable = false
            override val rewindUsage = RewindUsage(40L * 1_048_576, 20L * 1_048_576)
        }

    private fun show(
        page: SharedPage,
        values: SettingValues,
    ) {
        rule.setContent { GooseStationTheme { SharedPageScreen(page, values, extras, onChange = {}, onBack = {}) } }
        rule.waitForIdle()
    }

    @Test
    fun emulationShowsTheRewindEstimate() {
        show(SharedPage.Emulation, pageValues(EmulationSettings.all, "Main/RewindEnable" to "true"))

        rule.onNodeWithText("using up to 40 MB of memory and 20 MB of video memory", substring = true).assertExists()
    }

    @Test
    fun graphicsSaysVulkanFailed() {
        show(SharedPage.Graphics, pageValues(GraphicsSettings.all))

        rule.onNodeWithText("Vulkan didn't start on this device", substring = true).assertExists()
    }

    @Test
    fun advancedSaysFastmemIsUnavailable() {
        show(SharedPage.Advanced, pageValues(AdvancedSettings.all))

        rule.onNodeWithText(FASTMEM_UNAVAILABLE, substring = true).assertExists()
    }

    @Test
    fun consoleNamesItsChoicesFromTheNames() {
        show(SharedPage.Console, pageValues(ConsoleSettings.all, "Console/Region" to "NTSC-J"))

        rule.onNodeWithText("NTSC-J (Japan)", substring = true).assertExists()
    }

    @Test
    fun audioShows() {
        show(SharedPage.Audio, pageValues(AudioSettings.all))

        rule.onNodeWithText("Audio").assertExists()
    }
}
