// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The library with a D-pad, on 30 fake games, the first with a real cover. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var games: List<GameEntry>
    private val started = mutableListOf<GameEntry>()

    @Before
    fun makeGames() {
        val cover = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "cover-01.png")
        cover.outputStream().use { out ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                .compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        games =
            (1..30).map { n ->
                val number = "%02d".format(n)
                GameEntry(
                    path = "content://test/tree/games/document/game-$number.cue",
                    title = "Game $number",
                    serial = "SLUS-000$number",
                    region = "NTSC-U",
                    totalPlayedSeconds = 0,
                    lastPlayedSeconds = 0,
                    coverPath = if (n == 1) cover.absolutePath else null,
                )
            }
    }

    private fun entry(n: Int) = games[n - 1]

    // Two columns of tiles (150 dp or more each), whatever the device: at most 460 dp, less the padding and insets.
    private fun show(
        games: List<GameEntry> = this.games,
        gridView: () -> Boolean = { true },
        focusPath: String? = null,
        focusSettings: Boolean = false,
    ) {
        rule.setContent {
            GooseStationTheme {
                Box(Modifier.width(460.dp)) {
                    LibraryScreen(
                        games = games,
                        gridView = gridView(),
                        banners = emptyList(),
                        message = null,
                        isError = false,
                        onToggleView = {},
                        onOpenSettings = {},
                        onRefresh = {},
                        onStartGame = { started += it },
                        focusPath = focusPath,
                        focusSettings = focusSettings,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun theLastLaunchedGameIsFocusedOnOpening() {
        show(focusPath = entry(25).path)

        rule.onNodeWithText("Game 25").assertIsFocused()
    }

    @Test
    fun theFirstGameIsFocusedWithoutALastLaunched() {
        show()

        rule.onNodeWithText("Game 01").assertIsFocused()
    }

    @Test
    fun rightThenEnterStartsTheNextGame() {
        show(focusPath = entry(25).path)

        press(Key.DirectionRight)
        press(Key.Enter)

        assertEquals(listOf(entry(26)), started)
    }

    @Test
    fun theListFocusesTheLastLaunchedGame() {
        show(gridView = { false }, focusPath = entry(25).path)

        rule.onNodeWithText("Game 25").assertIsFocused()
        rule.onNodeWithText("NTSC-U · SLUS-00025 · Never played").assertExists()
    }

    @Test
    fun togglingTheViewKeepsTheFocusedGame() {
        var grid by mutableStateOf(true)
        show(gridView = { grid }, focusPath = entry(25).path)
        press(Key.DirectionRight)
        rule.onNodeWithText("Game 26").assertIsFocused()

        grid = false
        rule.waitForIdle()

        rule.onNodeWithText("NTSC-U · SLUS-00026 · Never played").assertExists()
        rule.onNodeWithText("Game 26").assertIsFocused()
    }

    @Test
    fun upFromTheFirstRowReachesTheTopBar() {
        show()

        press(Key.DirectionUp)

        rule.onNode(isFocused()).assert(hasText("List") or hasText("Settings") or hasText("Refresh"))
    }

    @Test
    fun aGameWithoutACoverShowsItsTitleInTheTile() {
        show()

        // The placeholder's title, and the one under the tile.
        rule.onAllNodesWithText("Game 02", useUnmergedTree = true).assertCountEquals(2)
        // A cover replaces the placeholder once it's loaded.
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Game 01", useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
    }

    @Test
    fun noGamesFocusesSettings() {
        show(games = emptyList())

        rule.onNodeWithText("No games found in your games folders.").assertExists()
        rule.onNodeWithText("Settings").assertIsFocused()
    }

    @Test
    fun comingBackFromTheSettingsFocusesSettings() {
        show(focusPath = entry(25).path, focusSettings = true)

        rule.onNodeWithText("Settings").assertIsFocused()
    }
}
