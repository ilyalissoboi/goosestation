// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.app.SyncBadge
import io.github.ilyalissoboi.goosestation.app.UpdateState
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.library.LibrarySort
import io.github.ilyalissoboi.goosestation.library.SortKey
import io.github.ilyalissoboi.goosestation.ui.components.ACTION_LABEL
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.BannerModel
import io.github.ilyalissoboi.goosestation.ui.components.CHOICE_LIST
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.update.Change
import io.github.ilyalissoboi.goosestation.update.Changes
import io.github.ilyalissoboi.goosestation.update.ChannelRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    private val gameSettings = mutableListOf<GameEntry>()
    private val saves = mutableListOf<GameEntry>()
    private var badgeClicks = 0
    private var sort by mutableStateOf(LibrarySort())
    private val sorts = mutableListOf<LibrarySort>()
    private val outerKeys = mutableListOf<Key>()

    @Before
    fun makeGames() {
        val cover = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "cover-01.png")
        cover.outputStream().use { out ->
            Bitmap
                .createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(Color.RED) }
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

    private companion object {
        /** The top bar's buttons, by their labels, in grid mode with the default sort. */
        val BUTTONS = listOf("Sort: Title", "Order: A to Z", "Show as a list", "Refresh the library", "Settings")
    }

    // Two columns of tiles (150 dp or more each), whatever the device: at most 460 dp, less the padding and insets.
    private fun show(
        games: List<GameEntry> = this.games,
        gridView: () -> Boolean = { true },
        focusPath: String? = null,
        focusSettings: Boolean = false,
        onToggleView: () -> Unit = {},
        syncBadge: SyncBadge? = null,
        sort: LibrarySort = LibrarySort(),
        search: TextFieldState = TextFieldState(),
        shownGames: (() -> List<GameEntry>)? = null,
        banners: List<BannerModel> = emptyList(),
        update: UpdateBannerModel? = null,
    ) {
        this.sort = sort
        rule.setContent {
            GooseStationTheme {
                // Sees the keys the screen leaves alone, as the activity would.
                Box(Modifier.width(460.dp).onKeyEvent { event -> false.also { outerKeys += event.key } }) {
                    LibraryScreen(
                        sort = this@LibraryScreenTest.sort,
                        onSort = {
                            sorts += it
                            this@LibraryScreenTest.sort = it
                        },
                        search = search,
                        games = shownGames?.invoke() ?: games,
                        gridView = gridView(),
                        banners = banners,
                        update = update,
                        message = null,
                        isError = false,
                        onToggleView = onToggleView,
                        onOpenSettings = {},
                        onRefresh = {},
                        onStartGame = { started += it },
                        onGameSettings = { gameSettings += it },
                        onSaves = { saves += it },
                        focusPath = focusPath,
                        focusSettings = focusSettings,
                        syncBadge = syncBadge,
                        onSyncBadge = { badgeClicks++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun focusedHas(text: String) =
        rule.onAllNodes(isFocused() and hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    // --- A game's own settings ---

    @Test
    fun yOnATileOpensItsSettings() {
        show(focusPath = entry(2).path)

        press(Key.ButtonY)

        assertEquals(listOf(entry(2)), gameSettings)
        assertEquals(emptyList<GameEntry>(), started)
    }

    @Test
    fun yInTheListOpensItsSettings() {
        show(gridView = { false }, focusPath = entry(3).path)

        press(Key.ButtonY)

        assertEquals(listOf(entry(3)), gameSettings)
    }

    @Test
    fun aLongPressOffersSavesAndGameSettings() {
        show()

        rule.onNodeWithText("Game 02").performTouchInput { longClick() }
        rule.waitForIdle()

        assertEquals(emptyList<GameEntry>(), gameSettings)
        assertEquals(emptyList<GameEntry>(), started)
        rule.onNode(isFocused() and hasText("Saves")).assertExists()
        rule.onNode(hasText("Game settings") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(listOf(entry(2)), gameSettings)
    }

    @Test
    fun theLongPressChoiceOpensSaves() {
        show()

        rule.onNodeWithText("Game 02").performTouchInput { longClick() }
        rule.waitForIdle()
        press(Key.Enter)

        assertEquals(listOf(entry(2)), saves)
    }

    @Test
    fun aLongPressOnAGameWithoutAKeyOpensSaves() {
        show(games = listOf(entry(1).copy(serial = "")))

        rule.onNodeWithText("Game 01").performTouchInput { longClick() }
        rule.waitForIdle()

        assertEquals(listOf(entry(1).copy(serial = "")), saves)
    }

    // Review: a choice the app refuses to open (not ready, a game running) leaves the tile focused.
    @Test
    fun aRefusedChoiceLeavesTheTileFocused() {
        show(focusPath = entry(2).path)

        rule.onNodeWithText("Game 02").performTouchInput { longClick() }
        rule.waitForIdle()
        press(Key.Enter)

        // The test's onSaves opens nothing, as a refusal does.
        assertEquals(listOf(entry(2)), saves)
        rule.onNode(isFocused() and hasText("Game 02", substring = true)).assertExists()
    }

    @Test
    fun bClosesTheChoiceBackToTheTile() {
        show(focusPath = entry(2).path)

        rule.onNodeWithText("Game 02").performTouchInput { longClick() }
        rule.waitForIdle()
        press(Key.ButtonB)

        assertEquals(emptyList<GameEntry>(), saves + gameSettings)
        rule.onNodeWithText("Saves").assertDoesNotExist()
        rule.onNode(isFocused() and hasText("Game 02", substring = true)).assertExists()
    }

    // --- A game's Saves ---

    @Test
    fun xOpensSaves() {
        show(focusPath = entry(2).path)

        press(Key.ButtonX)

        assertEquals(listOf(entry(2)), saves)
        assertEquals(emptyList<GameEntry>(), started)
    }

    @Test
    fun xInTheListOpensSaves() {
        show(gridView = { false }, focusPath = entry(3).path)

        press(Key.ButtonX)

        assertEquals(listOf(entry(3)), saves)
    }

    @Test
    fun xOnAGameWithoutAKeyOpensSaves() {
        show(games = listOf(entry(1).copy(serial = "")))

        press(Key.ButtonX)

        assertEquals(listOf(entry(1).copy(serial = "")), saves)
    }

    @Test
    fun aBlankKeyHasNoGameSettings() {
        show(games = listOf(entry(1).copy(serial = "")))

        press(Key.ButtonY)

        assertEquals(emptyList<GameEntry>(), gameSettings)
    }

    @Test
    fun theFooterSaysYGameSettings() {
        show()

        rule.onNodeWithText("Game settings", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Play", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("${PadGlyphs.action}: Saves").assertExists()
    }

    @Test
    fun noFooterWithoutGames() {
        show(games = emptyList())

        rule.onNodeWithText("Game settings", useUnmergedTree = true).assertDoesNotExist()
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
    fun theToggleButtonHandsTheFocusToTheGame() {
        var grid by mutableStateOf(true)
        show(gridView = { grid }, focusPath = entry(25).path, onToggleView = { grid = !grid })
        rule.onNodeWithContentDescription("Show as a list").requestFocus()

        press(Key.Enter)

        rule.onNodeWithText("NTSC-U · SLUS-00025 · Never played").assertExists()
        rule.onNodeWithText("Game 25").assertIsFocused()
    }

    @Test
    fun upFromTheFirstGameReachesTheSearchThenTheButtons() {
        show()

        press(Key.DirectionUp)
        rule.onNode(hasSetTextAction()).assertIsFocused()

        press(Key.DirectionUp)
        rule
            .onNode(isFocused())
            .assert(BUTTONS.map { hasContentDescription(it) }.reduce { a, b -> a or b })
    }

    // --- Sorting ---

    private fun openSort(label: String = "Sort: Title") {
        rule.onNodeWithContentDescription(label).requestFocus()
        rule.waitForIdle()
        press(Key.Enter)
        rule.onNodeWithTag(CHOICE_LIST).assertIsDisplayed()
    }

    @Test
    fun sortOpensItsListWithTheCurrentChoiceFocused() {
        show()

        openSort()

        rule.onNodeWithTag(CHOICE_LIST).assertIsDisplayed()
        rule.onNode(isFocused()).assert(hasText("Title"))
    }

    @Test
    fun pickingASortCallsOnSortAndFocusesSort() {
        show()
        openSort()

        press(Key.DirectionDown)
        press(Key.Enter)

        assertEquals(listOf(LibrarySort(SortKey.LastPlayed, false)), sorts)
        rule.onNodeWithTag(CHOICE_LIST).assertDoesNotExist()
        rule.onNodeWithContentDescription("Sort: Last played").assertIsFocused()
    }

    @Test
    fun pickingTheCurrentSortChangesNothing() {
        show()
        openSort()

        press(Key.Enter)

        assertEquals(emptyList<LibrarySort>(), sorts)
        rule.onNodeWithTag(CHOICE_LIST).assertDoesNotExist()
        rule.onNodeWithContentDescription("Sort: Title").assertIsFocused()
    }

    @Test
    fun bClosesTheSortListAndKeepsTheSearch() {
        val search = TextFieldState("game")
        show(search = search)
        openSort()

        press(Key.ButtonB)

        rule.onNodeWithTag(CHOICE_LIST).assertDoesNotExist()
        rule.onNodeWithContentDescription("Sort: Title").assertIsFocused()
        rule.runOnIdle { assertEquals("game", search.text.toString()) }
        assertEquals(emptyList<LibrarySort>(), sorts)
    }

    @Test
    fun aTapOutsideClosesTheSortList() {
        show()
        openSort()

        rule.onNodeWithText("Game 03").performClick()

        rule.onNodeWithTag(CHOICE_LIST).assertDoesNotExist()
        assertEquals(emptyList<GameEntry>(), started)
    }

    @Test
    fun theSortListIsInTheScreen() {
        show()
        openSort()

        rule.onAllNodes(isRoot()).assertCountEquals(1)
    }

    @Test
    fun aDesktopOnlySortShowsByName() {
        show(sort = LibrarySort(SortKey.Type))

        rule.onNodeWithContentDescription("Order: Discs first").assertExists()
        openSort("Sort: Type")

        // Last, after the six the library offers, and current.
        rule.onNode(isFocused()).assert(hasText("Type"))
        press(Key.DirectionUp)
        rule.onNode(isFocused()).assert(hasText("File size"))
    }

    @Test
    fun orderReverses() {
        show()
        rule.onNodeWithContentDescription("Order: A to Z").requestFocus()
        rule.waitForIdle()

        press(Key.Enter)

        assertEquals(listOf(LibrarySort(SortKey.Title, true)), sorts)
        rule.onNodeWithContentDescription("Order: Z to A").assertIsFocused()
    }

    @Test
    fun theGamesFollowTheSort() {
        show(games = games.map { it.copy(lastPlayedSeconds = it.title.takeLast(2).toLong()) })
        rule.onNodeWithText("30 games · Title, A–Z").assertExists()

        rule.runOnIdle { sort = LibrarySort(SortKey.LastPlayed) }
        rule.waitForIdle()

        rule.onNodeWithText("30 games · Last played, most recent first").assertExists()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onAllNodes(hasText("Game ", substring = true))[0].assert(hasText("Game 30"))
    }

    @Test
    fun afterASortTheLastFocusedGameIsInView() {
        show(
            games = games.map { if (it.title == "Game 25") it.copy(totalPlayedSeconds = 3_600) else it },
            focusPath = entry(25).path,
        )
        rule.onNodeWithText("Game 25").assertIsFocused()

        openSort()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.Enter)

        assertEquals(listOf(LibrarySort(SortKey.TimePlayed, false)), sorts)
        rule.onNodeWithContentDescription("Sort: Time played").assertIsFocused()
        // Played the most, it's last now: the grid has gone to it.
        rule.onNodeWithText("Game 25").assertIsDisplayed()
    }

    @Test
    fun aSortKeepsInViewAGameThatWasntTheFirstShown() {
        show(games = games.map { if (it.title == "Game 01") it.copy(totalPlayedSeconds = 3_600) else it })
        // Game 02, beside the first game shown, Game 01.
        press(Key.DirectionRight)
        rule.onNodeWithText("Game 02").assertIsFocused()

        // Time played sends Game 01, which the grid follows, to the end; Game 02 moves up to Game 01's place.
        openSort()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.Enter)

        rule.onNodeWithText("Game 02").assertIsDisplayed()
    }

    // From testing on the Thor: cancelling the resume prompt, which the activity draws over the library, left the
    // focus on the search field rather than on the game being started.
    @Test
    fun aGameGetsTheFocusBackWhenAnOverlayCloses() {
        var open by mutableStateOf(false)
        rule.setContent {
            GooseStationTheme {
                Box(Modifier.width(460.dp)) {
                    LibraryScreen(
                        search = TextFieldState(),
                        games = games,
                        gridView = true,
                        banners = emptyList(),
                        message = null,
                        isError = false,
                        onToggleView = {},
                        onOpenSettings = {},
                        onRefresh = {},
                        onStartGame = { open = true },
                        focusPath = null,
                        overlayOpen = open,
                    )
                    if (open) {
                        OverlayPanel(onBack = { open = false }, title = "Panel") {
                            val cancel = remember { FocusRequester() }
                            LaunchedEffect(Unit) { cancel.requestFocus() }
                            ActionButton(
                                "Cancel",
                                onClick = { open = false },
                                modifier = Modifier.focusRequester(cancel),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        rule.onNodeWithText("Game 04").assertIsFocused()

        press(Key.Enter)
        rule.onNodeWithText("Cancel").assertIsFocused()
        press(Key.Enter)

        rule.onNodeWithText("Game 04").assertIsFocused()
    }

    // From testing on the Thor: Down from a tile that wasn't the first in its row went to the next row's first.
    @Test
    fun downGoesToTheGameUnderneath() {
        show()
        press(Key.DirectionRight)
        rule.onNodeWithText("Game 02").assertIsFocused()

        press(Key.DirectionDown)
        rule.onNodeWithText("Game 04").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithText("Game 06").assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithText("Game 04").assertIsFocused()
    }

    @Test
    fun downOntoAShortLastRowGoesToItsLastGame() {
        show(games = games.take(29), focusPath = entry(28).path)
        rule.onNodeWithText("Game 28").assertIsFocused()

        press(Key.DirectionDown)

        rule.onNodeWithText("Game 29").assertIsFocused()
    }

    // --- Searching ---

    private fun field() = rule.onNode(hasSetTextAction())

    private fun type(text: String) {
        field().performTextInput(text)
        rule.waitForIdle()
    }

    private fun hint(
        glyph: String,
        action: String,
    ) = rule.onNodeWithContentDescription("$glyph: $action")

    @Test
    fun typingNarrowsTheGames() {
        show()

        type("game 2")

        rule.onNodeWithText("10 of 30 games · Title, A–Z").assertExists()
        rule.onNodeWithText("Game 20").assertExists()
        rule.onNodeWithText("Game 02").assertDoesNotExist()
        rule.onNodeWithText("Game 19").assertDoesNotExist()
    }

    @Test
    fun aMatchIsMarked() {
        show(gridView = { false })

        type("e 2")

        val title =
            rule
                .onNodeWithText("Game 20")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single { it.text == "Game 20" }
        assertEquals(listOf(3 until 6), title.spanStyles.map { it.start until it.end })
    }

    @Test
    fun selectJumpsToTheSearch() {
        show()
        rule.onNodeWithText("Game 01").assertIsFocused()

        press(Key.ButtonSelect)

        field().assertIsFocused()
    }

    @Test
    fun downFromTheFieldGoesToTheFirstMatch() {
        show()
        type("game 2")

        press(Key.DirectionDown)

        rule.onNodeWithText("Game 20").assertIsFocused()
    }

    @Test
    fun bClearsTheSearchAndKeepsTheGame() {
        show()
        type("game 2")
        press(Key.DirectionDown)

        press(Key.ButtonB)

        rule.onNodeWithText("30 games · Title, A–Z").assertExists()
        rule.onNodeWithText("Game 20").assertIsFocused().assertIsDisplayed()
        assertFalse(outerKeys.contains(Key.ButtonB))
    }

    @Test
    fun bWithoutASearchIsLeftAlone() {
        show()

        press(Key.ButtonB)

        assertTrue(outerKeys.contains(Key.ButtonB))
    }

    @Test
    fun spacesAloneAreNoSearch() {
        show()

        type("   ")

        rule.onNodeWithText("30 games · Title, A–Z").assertExists()
        press(Key.ButtonB)
        assertTrue(outerKeys.contains(Key.ButtonB))
    }

    @Test
    fun noMatchSaysSoAndWaitsForTheKeyboard() {
        show()
        type("zelda")

        rule.onNodeWithText("No games match “zelda”.").assertExists()
        field().assertIsFocused()

        field().performImeAction()
        rule.waitForIdle()
        rule.onNodeWithText("Clear search").assertIsFocused()

        press(Key.Enter)
        assertEquals("", field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        field().assertIsFocused()
    }

    @Test
    fun bOnClearSearchClearsAndFocusesTheField() {
        show()
        type("zelda")
        press(Key.DirectionDown)
        rule.onNodeWithText("Clear search").assertIsFocused()

        press(Key.ButtonB)

        rule.onNodeWithText("30 games · Title, A–Z").assertExists()
        field().assertIsFocused()
    }

    @Test
    fun searchingAnEmptyLibraryKeepsTheField() {
        show(games = emptyList())
        type("zelda")
        assertEquals("zelda", field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        field().assertIsFocused()

        // No games, so no Clear search to go to: the field keeps the focus, and nothing asks for a button that isn't
        // there (Compose prints "FocusRequester is not initialized" for it, or throws in other versions).
        val printed = java.io.ByteArrayOutputStream()
        val out = System.out
        System.setOut(java.io.PrintStream(printed, true))
        try {
            press(Key.DirectionDown)
            field().performImeAction()
            rule.waitForIdle()
        } finally {
            System.setOut(out)
        }

        field().assertIsFocused()
        assertFalse(printed.toString(), printed.toString().contains("FocusRequester is not initialized"))
    }

    @Test
    fun downOnNoMatchGoesToClearSearch() {
        show()
        type("zelda")

        press(Key.DirectionDown)

        rule.onNodeWithText("Clear search").assertIsFocused()
    }

    @Test
    fun theFooterFollowsTheFocus() {
        show()
        // On a game.
        hint(PadGlyphs.confirm, "Play").assertExists()
        hint(PadGlyphs.action, "Saves").assertExists()
        hint(PadGlyphs.options, "Game settings").assertExists()
        hint(PadGlyphs.select, "Search").assertExists()
        hint(PadGlyphs.back, "Clear search").assertDoesNotExist()

        // On the field, with a search.
        type("game")
        hint(PadGlyphs.confirm, "Type").assertExists()
        hint(PadGlyphs.back, "Clear search").assertExists()
        hint(PadGlyphs.confirm, "Play").assertDoesNotExist()

        // On a game while searching: Clear search in Search's place.
        press(Key.DirectionDown)
        hint(PadGlyphs.confirm, "Play").assertExists()
        hint(PadGlyphs.back, "Clear search").assertExists()
        hint(PadGlyphs.select, "Search").assertDoesNotExist()
        press(Key.ButtonB)

        // On a top-bar button.
        rule.onNodeWithContentDescription("Sort: Title").requestFocus()
        rule.waitForIdle()
        hint(PadGlyphs.confirm, "Choose").assertExists()
        hint(PadGlyphs.select, "Search").assertExists()

        // With the sort list open.
        press(Key.Enter)
        hint(PadGlyphs.confirm, "Choose").assertExists()
        hint(PadGlyphs.back, "Close").assertExists()
        hint(PadGlyphs.select, "Search").assertDoesNotExist()
    }

    private val release = ChannelRelease("preview", "0.1-512-g1a2b3c4", 100_512, "a".repeat(40), "http://x/apk", 4)

    private fun updateModel(onHide: () -> Unit = {}) =
        UpdateBannerModel(
            state = UpdateState.Available(release),
            ownVersion = "0.1-498-gfffffff",
            failureMessage = null,
            onUpdate = {},
            onSkip = {},
            onHide = onHide,
            onCancel = {},
            onOpenSettings = {},
            loadChanges = { Changes(listOf(Change("Android: a banner", "Ada")), total = 1) },
        )

    @Test
    fun theUpdateBannerSitsAfterTheFolderBanners() {
        show(
            banners = listOf(BannerModel("The games folder can't be reached.", listOf("Pick again" to {}))),
            update = updateModel(),
        )
        val folder = rule.onNodeWithText("Pick again").fetchSemanticsNode().boundsInRoot
        val update = rule.onNodeWithText("Skip this version").fetchSemanticsNode().boundsInRoot
        assertTrue("$folder, $update", folder.bottom <= update.top)
    }

    @Test
    fun theUpdateBannerDoesntTakeTheFocus() {
        show(update = updateModel())
        hint(PadGlyphs.confirm, "Play").assertExists()
    }

    @Test
    fun anUpdateBannersButtonSaysBHide() {
        var hidden = 0
        show(update = updateModel(onHide = { hidden++ }))
        rule.onNodeWithText("Update").requestFocus()
        rule.waitForIdle()
        hint(PadGlyphs.confirm, "Choose").assertExists()
        hint(PadGlyphs.back, "Hide").assertExists()
        press(Key.ButtonB)
        assertEquals(1, hidden)
    }

    // B hides the banner, which had the focus: the focus goes back to the game, not to the top of the screen.
    @Test
    fun hidingTheUpdateBannerGivesTheFocusBackToTheGame() {
        var shown by mutableStateOf(true)
        rule.setContent {
            GooseStationTheme {
                LibraryScreen(
                    games = games,
                    gridView = true,
                    banners = emptyList(),
                    update = if (shown) updateModel(onHide = { shown = false }) else null,
                    message = null,
                    isError = false,
                    onToggleView = {},
                    onOpenSettings = {},
                    onRefresh = {},
                    onStartGame = {},
                    focusPath = games[1].path,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Update").requestFocus()
        press(Key.ButtonB)
        assertFalse(shown)
        rule.onNodeWithText(games[1].title).assertIsFocused()
    }

    @Test
    fun whatsNewOpensOverTheLibraryAndGivesTheFocusBack() {
        show(update = updateModel())
        rule.onNodeWithText("What's new").requestFocus()
        press(Key.Enter)
        rule.onNodeWithText("What's new in 0.1-512").assertExists()
        rule.onNodeWithText("Android: a banner", substring = true).assertIsFocused()
        press(Key.ButtonB)
        rule.onNodeWithText("What's new in 0.1-512").assertDoesNotExist()
        rule.onNodeWithText("What's new").assertIsFocused()
    }

    @Test
    fun aBannersButtonSaysAChoose() {
        show(banners = listOf(BannerModel("The games folder can't be reached.", listOf("Pick again" to {}))))
        hint(PadGlyphs.confirm, "Play").assertExists()

        rule.onNodeWithText("Pick again").requestFocus()
        rule.waitForIdle()

        hint(PadGlyphs.confirm, "Choose").assertExists()
        hint(PadGlyphs.select, "Search").assertExists()
        hint(PadGlyphs.confirm, "Play").assertDoesNotExist()
        hint(PadGlyphs.action, "Saves").assertDoesNotExist()
    }

    @Test
    fun aNewListWhileSearchingKeepsTheFocusedGame() {
        var shown by mutableStateOf(games)
        show(shownGames = { shown })
        type("game 2")
        press(Key.DirectionDown)
        rule.onNodeWithText("Game 20").assertIsFocused()

        rule.runOnIdle {
            shown =
                games.map { it.copy() } +
                games[0].copy(path = "content://test/tree/games/document/game-2a.cue", title = "Game 2A")
        }
        rule.waitForIdle()

        rule.onNodeWithText("Game 20").assertIsFocused()
        rule.onNodeWithText("11 of 31 games · Title, A–Z").assertExists()
    }

    @Test
    fun aThousandGames() {
        val many =
            (1..1000).map { n ->
                GameEntry(
                    path = "content://test/tree/games/document/game-$n.cue",
                    title = "Game %03d".format(n),
                    serial = "SLUS-%05d".format(n),
                    region = "NTSC-U",
                    totalPlayedSeconds = 0,
                    lastPlayedSeconds = 0,
                    coverPath = null,
                )
            }
        show(games = many)

        type("game 99")

        // Game 990 to Game 999.
        rule.onNodeWithText("10 of 1000 games · Title, A–Z").assertExists()
        press(Key.DirectionDown)
        rule.onNodeWithText("Game 990").assertIsFocused()
    }

    // --- The top bar ---

    @Test
    fun theButtonsAreIconsNamedByTheirLabels() {
        show()

        for (name in BUTTONS) {
            rule.onNodeWithContentDescription(name).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        }
        rule.onNodeWithContentDescription("Show as a grid").assertDoesNotExist()
    }

    @Test
    fun theViewButtonShowsTheGridInListMode() {
        show(gridView = { false })

        rule.onNodeWithContentDescription("Show as a grid").assertExists()
        rule.onNodeWithContentDescription("Show as a list").assertDoesNotExist()
    }

    @Test
    fun aFocusedButtonShowsItsLabel() {
        show(focusSettings = true)

        rule.onNodeWithTag(ACTION_LABEL, useUnmergedTree = true).assertTextEquals("Settings")
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
        rule.onNodeWithContentDescription("Settings").assertIsFocused()
    }

    @Test
    fun comingBackFromTheSettingsFocusesSettings() {
        show(focusPath = entry(25).path, focusSettings = true)

        rule.onNodeWithContentDescription("Settings").assertIsFocused()
    }

    @Test
    fun stalePendingFocusIsDropped() {
        // Settings takes the focus after the screen asks for the last game, before the grid has composed it: the
        // test's effect runs after the screen's, and the game's own effect, which focuses it, comes after both.
        rule.setContent {
            val focusManager = LocalFocusManager.current
            GooseStationTheme {
                Box(Modifier.width(460.dp)) {
                    LibraryScreen(
                        games = games,
                        gridView = true,
                        banners = emptyList(),
                        message = null,
                        isError = false,
                        onToggleView = {},
                        onOpenSettings = {},
                        onRefresh = {},
                        onStartGame = {},
                        focusPath = entry(30).path,
                    )
                }
            }
            LaunchedEffect(Unit) {
                // Sort, first in the bar by place: at this width the buttons are over the search field.
                focusManager.moveFocus(FocusDirection.Next)
            }
        }
        rule.waitForIdle()

        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        rule.waitForIdle()

        rule.onNodeWithText("Game 30").assertExists()
        rule.onNodeWithContentDescription("Sort: Title").assertIsFocused()
    }

    @Test
    fun badgeShowsWordAndIcon() {
        show(syncBadge = SyncBadge.Conflicts(2))

        rule.onNodeWithText("2 conflicts").assertExists()
    }

    @Test
    fun noBadgeWhenOff() {
        show(syncBadge = null)

        for (word in listOf("Synced", "Syncing…", "Offline", "Needs setup", "Sync failed")) {
            assertEquals(0, rule.onAllNodes(hasText(word)).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun badgeIsFocusableAndOpens() {
        show(syncBadge = SyncBadge.Synced, focusSettings = true)

        // Left of the five buttons, Settings the last; or beside the title, over them, on a narrow screen.
        repeat(5) { if (!focusedHas("Synced")) press(Key.DirectionLeft) }
        if (!focusedHas("Synced")) press(Key.DirectionUp)
        rule.onNode(isFocused()).assert(hasText("Synced"))
        press(Key.ButtonA)

        assertEquals(1, badgeClicks)
    }
}
