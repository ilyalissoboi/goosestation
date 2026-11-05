// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Graphics page with a D-pad: each row changes its own setting and no other. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GraphicsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val start = pageValues(GraphicsSettings.all)
    private val changes = mutableListOf<SettingValues>()

    private fun show(
        values: SettingValues = start,
        vulkanFailed: Boolean = false,
    ) {
        rule.setContent {
            GooseStationTheme {
                // The values as shown stay the same, so each change is made from them.
                GraphicsScreen(values, SampleNames.ALL, vulkanFailed, onChange = { changes += it }, onBack = {})
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertFocusedHas(text: String) {
        rule.onNode(isFocused()).assert(hasText(text, substring = true))
    }

    @Test
    fun eachRowChangesOnlyItsSetting() {
        show()

        assertFocusedHas("Renderer")
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        assertFocusedHas("Internal resolution")
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        assertFocusedHas("Aspect ratio")
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        assertFocusedHas("Show FPS")
        press(Key.ButtonA)

        assertEquals(
            listOf(
                start.with(GraphicsSettings.RENDERER, "Vulkan"),
                start.with(GraphicsSettings.RESOLUTION_SCALE, 2),
                start.with(GraphicsSettings.ASPECT_RATIO, "Stretch To Fill"),
                start.with(GraphicsSettings.SHOW_FPS, true),
            ),
            changes,
        )
    }

    @Test
    fun openGlIsCalledOpenGlEs() {
        show(pageValues(GraphicsSettings.all, "GPU/Renderer" to "OpenGL"))

        rule.onNodeWithText("OpenGL ES").assertExists()
    }

    @Test
    fun theRendererSaysWhenVulkanFailed() {
        show(vulkanFailed = true)

        rule.onNodeWithText("Vulkan didn't start on this device", substring = true).assertExists()
    }

    @Test
    fun aRendererTheCoreDoesntNameIsKept() {
        show(pageValues(GraphicsSettings.all, "GPU/Renderer" to "Metal"))

        // Marked, and last: Left steps back into the core's own.
        assertFocusedHas("Metal (from settings.ini)")
        press(Key.DirectionLeft)
        assertEquals(listOf(start.with(GraphicsSettings.RENDERER, "Software")), changes)
    }
}
