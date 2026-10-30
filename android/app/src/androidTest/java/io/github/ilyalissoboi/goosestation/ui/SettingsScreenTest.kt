// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The settings hub, and the Graphics page, with a D-pad. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    private companion object {
        const val UNAVAILABLE = "Available once GooseStation has started."
    }

    @get:Rule val rule = createComposeRule()

    private val opened = mutableListOf<SettingsEntry>()
    private var backs = 0

    private fun show(
        focusEntry: SettingsEntry?,
        coreReady: Boolean = true,
    ) {
        rule.setContent {
            GooseStationTheme {
                SettingsScreen(
                    focusEntry = focusEntry,
                    coreReady = coreReady,
                    onOpen = { opened += it },
                    onBack = { backs++ },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertFocusedHas(vararg texts: String) {
        texts.forEach { rule.onNode(isFocused()).assert(hasText(it)) }
    }

    @Test
    fun controllersIsFocusedWithoutAnEntry() {
        show(focusEntry = null)

        assertFocusedHas("Controllers", "Remap each controller's buttons")
    }

    @Test
    fun theEntryLeftIsFocused() {
        show(focusEntry = SettingsEntry.Bios)

        assertFocusedHas("BIOS", "Which BIOS each region uses, fast boot")
    }

    @Test
    fun theEntriesAreInOrder() {
        show(focusEntry = null)

        press(Key.DirectionDown)
        assertFocusedHas("Graphics", "Renderer, resolution, aspect ratio, FPS")
        press(Key.DirectionDown)
        assertFocusedHas("BIOS")
        press(Key.DirectionDown)
        assertFocusedHas("Folders", "BIOS, games and data folders")
        press(Key.Enter)

        assertEquals(listOf(SettingsEntry.Folders), opened)
    }

    @Test
    fun withoutTheCoreFoldersIsFocused() {
        show(focusEntry = null, coreReady = false)

        assertFocusedHas("Folders", "BIOS, games and data folders")
    }

    @Test
    fun withoutTheCoreTheEntriesLeftFallToFolders() {
        show(focusEntry = SettingsEntry.Graphics, coreReady = false)

        assertFocusedHas("Folders")
    }

    @Test
    fun withoutTheCoreTheOtherEntriesCantBeFocusedOrOpened() {
        show(focusEntry = null, coreReady = false)

        for (label in listOf("Controllers", "Graphics", "BIOS")) {
            val entry = rule.onNode(hasText(label) and hasText(UNAVAILABLE))
            entry.assertIsNotEnabled()
            // Not focusable: no way to request the focus, and so neither the D-pad nor anything else can.
            entry.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
            entry.assert(!isFocused())
            entry.performClick()
            rule.waitForIdle()
        }
        // Up from Folders reaches nothing above it.
        press(Key.DirectionUp)
        assertFocusedHas("Folders")
        press(Key.Enter)

        assertEquals(listOf(SettingsEntry.Folders), opened)
    }

    @Test
    fun backGoesBack() {
        show(focusEntry = null)

        press(Key.Back)

        assertEquals(1, backs)
    }

    @Test
    fun buttonBGoesBack() {
        show(focusEntry = SettingsEntry.Graphics)

        press(Key.ButtonB)

        assertEquals(1, backs)
    }

    @Test
    fun theGraphicsPageFocusesTheRendererWithItsNotes() {
        rule.setContent {
            GooseStationTheme {
                GraphicsScreen(
                    settings = GraphicsSettings("Automatic", 3, "Auto (Game Native)", false),
                    aspectNames = listOf("Auto (Game Native)", "Stretch To Fill", "Custom", "4:3"),
                    vulkanFailed = true,
                    onChange = {},
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()

        assertFocusedHas("Renderer", "Automatic (Vulkan where it works well)")
        rule
            .onNode(hasText("Vulkan didn't start on this device, so games use OpenGL ES until the app restarts."))
            .assertExists()
        rule
            .onNode(hasText("Higher is sharper and slower. Match the screen picks the highest that fits."))
            .assertExists()
    }

    @Test
    fun theGraphicsPageChangesOneFieldAtATime() {
        val changes = mutableListOf<GraphicsSettings>()
        val settings = GraphicsSettings("Automatic", 1, "Auto (Game Native)", false)
        rule.setContent {
            GooseStationTheme {
                GraphicsScreen(
                    settings = settings,
                    aspectNames = listOf("Auto (Game Native)", "Stretch To Fill"),
                    vulkanFailed = false,
                    onChange = { changes += it },
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNode(hasText("Vulkan didn't start on this device, so games use OpenGL ES until the app restarts."))
            .assertDoesNotExist()
        rule.onNode(hasText("Higher is sharper and slower. Match the screen picks the highest that fits."))
            .assertDoesNotExist()

        // Renderer, Internal resolution, Aspect ratio, then Show FPS.
        repeat(3) { press(Key.DirectionDown) }
        assertFocusedHas("Show FPS", "Off")
        press(Key.Enter)

        assertEquals(listOf(settings.copy(showFps = true)), changes)
    }
}
