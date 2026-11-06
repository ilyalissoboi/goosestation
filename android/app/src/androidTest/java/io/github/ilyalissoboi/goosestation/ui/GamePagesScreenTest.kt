// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.settings.AudioSettings
import io.github.ilyalissoboi.goosestation.settings.ConsoleSettings
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.MemoryCardSettings
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.GameContext
import io.github.ilyalissoboi.goosestation.ui.components.LocalGameContext
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The pages with a game's values: every row can use the global setting, the rows of several keys too. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GamePagesScreenTest {
    @get:Rule val rule = createComposeRule()

    private var shown: SettingValues? = null

    private fun show(
        start: SettingValues,
        page: @Composable (SettingValues, (SettingValues) -> Unit) -> Unit,
    ) {
        rule.setContent {
            GooseStationTheme {
                var values by remember { mutableStateOf(start) }
                shown = values
                CompositionLocalProvider(LocalGameContext provides GameContext("Pad Test", false, false)) {
                    page(values) { values = it }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedText(): String =
        rule
            .onAllNodes(isFocused())
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.let { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } }
            .orEmpty()

    private fun focusedHas(text: String) =
        rule.onAllNodes(isFocused() and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun downTo(text: String) {
        repeat(80) {
            if (focusedHas(text)) return
            press(Key.DirectionDown)
        }
        throw AssertionError("Down never reached $text")
    }

    // Every row, top to bottom: each one's text, until Down no longer moves.
    private fun rows(): List<String> {
        val seen = mutableListOf<String>()
        repeat(120) {
            val text = focusedText()
            if (seen.lastOrNull() == text) return seen
            seen += text
            press(Key.DirectionDown)
        }
        return seen
    }

    private fun assertEveryRowCanUseGlobal(rows: List<String>) {
        assertTrue("some rows", rows.size > 3)
        for (row in rows) assertTrue("'$row' can use the global setting", "Use global" in row)
    }

    @Test
    fun overclockUseGlobalUnsetsBothTerms() {
        val game =
            mapOf("CPU/OverclockEnable" to "true", "CPU/OverclockNumerator" to "2", "CPU/OverclockDenominator" to "1")
        show(gamePageValues(ConsoleSettings.all, game = game)) { values, onChange ->
            ConsoleScreen(values, SampleNames.ALL, onChange, onBack = {})
        }
        downTo("Overclocking percentage")
        assertTrue(focusedHas("200%"))
        assertTrue(focusedHas(SET_FOR_GAME))

        press(Key.ButtonA)
        repeat(12) { press(Key.DirectionUp) }
        press(Key.ButtonA)

        assertFalse(shown!!.isSet(ConsoleSettings.OVERCLOCK_NUMERATOR))
        assertFalse(shown!!.isSet(ConsoleSettings.OVERCLOCK_DENOMINATOR))
        assertTrue(shown!!.isSet(ConsoleSettings.OVERCLOCK))
    }

    @Test
    fun multisamplingPickSetsBothKeys() {
        show(gamePageValues(GraphicsSettings.all)) { values, onChange ->
            GraphicsScreen(values, SampleNames.ALL, vulkanFailed = false, onChange = onChange, onBack = {})
        }
        downTo("Multisampling")
        assertTrue(focusedHas("Use global (Off)"))

        press(Key.DirectionRight)

        assertTrue(shown!!.isSet(GraphicsSettings.MULTISAMPLES))
        assertTrue(shown!!.isSet(GraphicsSettings.PER_SAMPLE_SHADING))
        assertTrue(focusedHas(SET_FOR_GAME))
    }

    @Test
    fun aTypeSettingRowIsAGames() {
        val settings = NEGCON_SETTINGS.take(2)
        show(gamePageValues(PortSettings.settings(0, settings))) { values, onChange ->
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { first.requestFocus() }
            SettingsPage("Port 1", onBack = {}) {
                TypeSettingRow(0, settings[0], values, onChange, Modifier.focusRequester(first))
                TypeSettingRow(0, settings[1], values, onChange)
            }
        }
        assertTrue(focusedHas("Use global (0%)"))

        press(Key.DirectionRight)

        assertTrue(shown!!.isSet(PortSettings.setting(0, settings[0])))
    }

    @Test
    fun aGamesSharedCardStartsWithUseGlobal() {
        show(gamePageValues(MemoryCardSettings.GAME, game = mapOf("MemoryCards/Card1Type" to "Shared"))) {
            values,
            onChange,
            ->
            SettingsPage("Memory cards", onBack = {}) {
                CardSlotRows(
                    CardSlots(values, SampleNames.ALL, listOf("mine.mcd"), onChange),
                    oneCardRow = false,
                    first = true,
                )
            }
        }
        assertTrue(focusedHas(SET_FOR_GAME))
        press(Key.DirectionDown)
        assertTrue(focusedHas("Shared card 1"))
        assertTrue(focusedHas("Use global ("))
        assertEquals(0, rule.onAllNodes(hasText("One card for all discs", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun everyRowOfConsoleCanUseGlobal() {
        show(gamePageValues(ConsoleSettings.all, global = mapOf("CPU/OverclockEnable" to "true"))) { values, onChange ->
            ConsoleScreen(values, SampleNames.ALL, onChange, onBack = {})
        }
        assertEveryRowCanUseGlobal(rows())
    }

    @Test
    fun everyRowOfEmulationCanUseGlobal() {
        show(gamePageValues(EmulationSettings.all)) { values, onChange ->
            EmulationScreen(values, null, onChange, onBack = {})
        }
        assertEveryRowCanUseGlobal(rows())
    }

    @Test
    fun everyRowOfGraphicsCanUseGlobal() {
        val global = mapOf("Display/AspectRatio" to "Custom", "GPU/DownsampleMode" to "Box")
        show(gamePageValues(GraphicsSettings.all, global = global)) { values, onChange ->
            GraphicsScreen(values, SampleNames.ALL, vulkanFailed = false, onChange = onChange, onBack = {})
        }
        assertEveryRowCanUseGlobal(rows())
    }

    @Test
    fun everyRowOfAudioCanUseGlobal() {
        show(gamePageValues(AudioSettings.all)) { values, onChange ->
            AudioScreen(values, SampleNames.ALL, onChange, onBack = {})
        }
        assertEveryRowCanUseGlobal(rows())
    }

    @Test
    fun everyCardRowCanUseGlobal() {
        val global = mapOf("MemoryCards/Card1Type" to "Shared", "MemoryCards/Card2Type" to "Shared")
        show(gamePageValues(MemoryCardSettings.GAME, global = global)) { values, onChange ->
            SettingsPage("Memory cards", onBack = {}) {
                CardSlotRows(
                    CardSlots(values, SampleNames.ALL, emptyList(), onChange),
                    oneCardRow = false,
                    first = true,
                )
            }
        }
        assertEveryRowCanUseGlobal(rows())
    }
}
