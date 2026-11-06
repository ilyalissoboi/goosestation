// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.ilyalissoboi.goosestation.app.GameControllers
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.GameContext
import io.github.ilyalissoboi.goosestation.ui.components.LocalGameContext
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A game's Controllers page with a D-pad: multitap, each port's type and its settings, or a profile's note. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GameControllersScreenTest {
    @get:Rule val rule = createComposeRule()

    private var shown: SettingValues? = null

    private fun show(
        controllers: GameControllers,
        game: Map<String, String> = emptyMap(),
    ) {
        val settings =
            listOf(PortSettings.MULTITAP) +
                controllers.pads.flatMap {
                    listOf(GameControllers.type(it)) + PortSettings.settings(it, controllers.typeSettings[it].orEmpty())
                }
        rule.setContent {
            GooseStationTheme {
                var values by remember {
                    mutableStateOf(
                        gamePageValues(settings, global = mapOf("Pad1/Type" to "AnalogController"), game = game),
                    )
                }
                shown = values
                CompositionLocalProvider(LocalGameContext provides GameContext("Pad Test", false, false)) {
                    GameControllersScreen(values, SampleNames.ALL, controllers, onChange = { values = it }, onBack = {})
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedHas(text: String) =
        rule.onAllNodes(isFocused() and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aGamesPortTypeCanUseGlobal() {
        show(GameControllers(listOf(0, 1), mapOf(0 to NEGCON_SETTINGS.take(1)), null))
        rule.onNode(isFocused()).assert(hasText("Multitap", substring = true))
        rule.onNodeWithText("Bindings are the global ones", substring = true).assertExists()

        press(Key.DirectionDown)
        assertTrue(focusedHas("Use global (Analog Controller (DualShock))"))
        press(Key.DirectionRight)

        assertTrue(shown!!.isSet(GameControllers.type(0)))
        // The type's own settings follow, each able to use the global setting.
        press(Key.DirectionDown)
        assertTrue(focusedHas("Use global ("))
    }

    @Test
    fun multitapOffersItsPorts() {
        show(
            GameControllers(listOf(0, 2, 3, 4, 1), emptyMap(), null),
            game =
                mapOf("ControllerPorts/MultitapMode" to "Port1Only"),
        )

        for (port in listOf("Port 1A", "Port 1B", "Port 1C", "Port 1D", "Port 2")) {
            rule.onNodeWithText(port, ignoreCase = true).assertExists()
        }
    }

    @Test
    fun anInputProfileMakesThePageReadOnly() {
        show(GameControllers(listOf(0, 1), emptyMap(), "Desk"))

        rule.onNode(isFocused()).assert(hasText("\"Desk\"", substring = true))
        rule.onNodeWithText("Multitap").assertDoesNotExist()
    }
}
