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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.GamePage
import io.github.ilyalissoboi.goosestation.app.GameQuestion
import io.github.ilyalissoboi.goosestation.app.GameSummary
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.ui.components.GameContext
import io.github.ilyalissoboi.goosestation.ui.components.LocalGameContext
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A game's settings hub with a D-pad: its summary, its pages, and Copy and Clear's questions. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GameSettingsScreenTest : ControllerKeys {
    @get:Rule override val rule = createComposeRule()

    private val game = GameEntry("/games/padtest.psexe", "Pad Test", "SLUS-00001", "NTSC-U", 0, 0, null)
    private val opened = mutableListOf<GamePage>()
    private val confirmed = mutableListOf<GameQuestion>()
    private var backs = 0

    private fun show(
        summary: GameSummary? = SUMMARY,
        focusEntry: GamePage? = null,
        context: GameContext? = null,
    ) {
        rule.setContent {
            GooseStationTheme {
                CompositionLocalProvider(LocalGameContext provides context) {
                    var asking by remember { mutableStateOf<GameQuestion?>(null) }
                    GameSettingsScreen(
                        game = game,
                        summary = summary,
                        focusEntry = focusEntry,
                        asking = asking,
                        onOpen = { opened += it },
                        onAsk = { asking = it },
                        onConfirm = {
                            confirmed += asking!!
                            asking = null
                        },
                        onCancel = { asking = null },
                        onBack = { backs++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theHubSaysWhatTheGameSets() {
        show()

        rule.onNodeWithText("Pad Test", ignoreCase = true).assertExists()
        rule.onNodeWithText("SLUS-00001", substring = true).assertExists()
        rule.onNodeWithText("3 settings of its own", substring = true).assertExists()
        rule.onNodeWithText("and 4 this app doesn't show", substring = true).assertExists()
        rule.onNode(isFocused()).assert(hasText("Console", substring = true))
        walkTo("Graphics")
        rule.onNode(isFocused()).assert(hasText("2 set for this game", substring = true))
    }

    @Test
    fun aGameWithNothingOfItsOwnSaysSo() {
        show(GameSummary(emptyMap(), 0))

        rule.onNodeWithText("No settings of its own", substring = true).assertExists()
    }

    @Test
    fun aPageOpensWithA() {
        show()
        walkTo("Audio")

        press(Key.ButtonA)

        assertEquals(listOf(GamePage.Audio), opened)
    }

    @Test
    fun copyAsksWithCancelFocused() {
        show()
        walkTo("Copy global settings")

        press(Key.ButtonA)
        rule.onNodeWithText("Copy the global settings to Pad Test?", substring = true).assertExists()
        rule.onNode(isFocused()).assert(hasText("Cancel"))
        press(Key.ButtonB)

        rule.onNodeWithText("Copy the global settings to Pad Test?", substring = true).assertDoesNotExist()
        assertEquals(emptyList<GameQuestion>(), confirmed)
        assertEquals(0, backs)
        rule.onNode(isFocused()).assert(hasText("Copy global settings"))
    }

    @Test
    fun clearConfirms() {
        show()
        walkTo("Clear settings")

        press(Key.ButtonA)
        rule.onNodeWithText("the 4 settings this app doesn't show", substring = true).assertExists()
        press(Key.DirectionLeft)
        press(Key.ButtonA)

        assertEquals(listOf(GameQuestion.Clear), confirmed)
    }

    @Test
    fun backFromAPageFocusesItsEntry() {
        show(focusEntry = GamePage.MemoryCards)

        rule.onNode(isFocused()).assert(hasText("Memory cards", substring = true))
    }

    @Test
    fun theHubHasNoGlobalPages() {
        show()

        for (page in listOf("Interface", "BIOS", "Folders", "Manage cards")) {
            rule.onNodeWithText(page).assertDoesNotExist()
        }
    }

    // Both write [Cheevos], whose Encore, Spectator and Test unofficial modes would end hardcore mode in the game.
    @Test
    fun copyAndClearWaitInAHardcoreGame() {
        show(context = GameContext("Pad Test", running = true, hardcoreActive = true))

        rule.onNodeWithText("Copy global settings").assertIsNotEnabled()
        rule.onNodeWithText("Clear settings").assertIsNotEnabled()
        rule.onNodeWithText(COPY_CLEAR_WAIT_NOTE, substring = true).assertExists()
    }

    @Test
    fun copyAndClearWorkInAGameWithoutHardcore() {
        show(context = GameContext("Pad Test", running = true, hardcoreActive = false))

        rule.onNodeWithText("Copy global settings").assertIsEnabled()
        rule.onNodeWithText(COPY_CLEAR_WAIT_NOTE, substring = true).assertDoesNotExist()
    }

    @Test
    fun bGoesBack() {
        show()

        press(Key.ButtonB)

        assertEquals(1, backs)
    }

    private companion object {
        val SUMMARY = GameSummary(mapOf(GamePage.Graphics to 2, GamePage.Console to 1), others = 4)
    }
}
