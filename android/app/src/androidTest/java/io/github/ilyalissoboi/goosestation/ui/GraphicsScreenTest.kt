// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Each of the Graphics page's choice rows, picked with a D-pad, changes its own field and no other. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GraphicsScreenTest {
    private companion object {
        val SETTINGS = GraphicsSettings("Automatic", 1, "Auto (Game Native)", false)
    }

    @get:Rule val rule = createComposeRule()

    private val changes = mutableListOf<GraphicsSettings>()

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertFocusedHas(text: String) {
        rule.onNode(isFocused()).assert(hasText(text))
    }

    // Steps the focused row on to the choice after its current one.
    private fun pickTheNextChoice() {
        press(Key.DirectionRight)
    }

    @Test
    fun eachRowChangesOnlyItsField() {
        rule.setContent {
            GooseStationTheme {
                // The settings as shown stay the same, so each change is made from them.
                GraphicsScreen(
                    settings = SETTINGS,
                    aspectNames = listOf("Auto (Game Native)", "Stretch To Fill"),
                    vulkanFailed = false,
                    onChange = { changes += it },
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()

        assertFocusedHas("Renderer")
        pickTheNextChoice()
        assertFocusedHas("Renderer")
        press(Key.DirectionDown)
        assertFocusedHas("Internal resolution")
        pickTheNextChoice()
        press(Key.DirectionDown)
        assertFocusedHas("Aspect ratio")
        pickTheNextChoice()

        assertEquals(
            listOf(
                SETTINGS.copy(renderer = "Vulkan"),
                SETTINGS.copy(resolutionScale = 2),
                SETTINGS.copy(aspectRatio = "Stretch To Fill"),
            ),
            changes,
        )
    }
}
