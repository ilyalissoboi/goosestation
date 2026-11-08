// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.SyncBadge
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.library.LibraryOrder
import io.github.ilyalissoboi.goosestation.library.LibrarySort
import io.github.ilyalissoboi.goosestation.library.LibraryWords
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.Banner
import io.github.ilyalissoboi.goosestation.ui.components.BannerModel
import io.github.ilyalissoboi.goosestation.ui.components.KeyHint
import io.github.ilyalissoboi.goosestation.ui.components.KeyHints
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.rememberSearchFocus
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons

/**
 * The games, as a cover grid ([gridView]) or a list, under the [LibraryTopBar]: the goose and "Library", the
 * [search] field, memory card sync's badge while sync is on ([syncBadge]; [onSyncBadge]), then icon buttons for the
 * [sort] ([onSort]), the view ([onToggleView]), Refresh ([onRefresh]) and Settings ([onOpenSettings]). [banners] and
 * [message] sit under the bar:
 * prompts to pick a folder again, the latest error ([isError]), or what the app is doing.
 *
 * On opening, Settings has focus when [focusSettings] (coming back from the settings), or else the game at
 * [focusPath] (the last one launched), or else the first game; with no games, Settings has it. Toggling the view keeps
 * the focused game. A, Enter or a tap call [onStartGame]; X opens the game's Saves ([onSaves]); Y opens its own
 * settings ([onGameSettings]), for a game with a key (its serial). A long press offers both, in an [OverlayPanel] (a
 * game without a key goes straight to its Saves). With games, the foot says so ([KeyHints]).
 */
@Composable
fun LibraryScreen(
    games: List<GameEntry>,
    gridView: Boolean,
    banners: List<BannerModel>,
    message: String?,
    isError: Boolean,
    onToggleView: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onStartGame: (GameEntry) -> Unit,
    focusPath: String?,
    focusSettings: Boolean = false,
    syncBadge: SyncBadge? = null,
    onSyncBadge: () -> Unit = {},
    onGameSettings: (GameEntry) -> Unit = {},
    onSaves: (GameEntry) -> Unit = {},
    sort: LibrarySort = LibrarySort(),
    onSort: (LibrarySort) -> Unit = {},
    search: TextFieldState = rememberTextFieldState(),
) {
    val settings = remember { FocusRequester() }
    val sortButton = remember { FocusRequester() }
    val searchFocus = rememberSearchFocus()
    val focusManager = LocalFocusManager.current
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val focus = remember { GameFocus() }
    val hasGames = games.isNotEmpty()
    // Only on opening: a toggle, or games coming in, focus a game as usual.
    var settingsFirst by remember { mutableStateOf(focusSettings) }
    // The game whose long press asks: Saves or Game settings.
    var choosing by remember { mutableStateOf<GameEntry?>(null) }

    fun longPress(game: GameEntry) = if (game.serial.isBlank()) onSaves(game) else choosing = game

    val sorted = remember(games, sort) { LibraryOrder.sorted(games, sort) }
    var sortOpen by remember { mutableStateOf(false) }
    // Where the Sort button is, and where this screen is, in the root: the list opens under the button.
    var sortBounds by remember { mutableStateOf(Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var refocusSort by remember { mutableStateOf(false) }

    fun closeSort() {
        sortOpen = false
        refocusSort = true
    }

    LaunchedEffect(refocusSort) {
        if (!refocusSort) return@LaunchedEffect
        // Once the list has gone, as a choice list gives the focus back to its row.
        withFrameNanos {}
        sortButton.requestFocus()
        refocusSort = false
    }

    // A new order keeps the game last focused in view, so Down from the bar reaches it wherever it moved. Not on
    // opening, which focuses a game of its own.
    var shownSort by remember { mutableStateOf(sort) }
    LaunchedEffect(sort) {
        if (sort == shownSort) return@LaunchedEffect
        shownSort = sort
        val index = sorted.indexOfFirst { it.path == focus.focused }
        if (index < 0) return@LaunchedEffect
        if (gridView) gridState.scrollToItem(index) else listState.scrollToItem(index)
    }

    // Not on every new list, which a resume brings: only on opening, on a toggle, and once there are games.
    LaunchedEffect(gridView, hasGames) {
        if (!hasGames || settingsFirst) {
            settingsFirst = false
            settings.requestFocus()
            return@LaunchedEffect
        }
        val index =
            listOf(focus.focused, focusPath).firstNotNullOfOrNull { path ->
                sorted.indexOfFirst { it.path == path }.takeIf { it >= 0 }
            } ?: 0
        // A lazy item can only take focus once it's composed: it does so itself, once the scroll brings it in.
        focus.pending = sorted[index].path
        if (gridView) gridState.scrollToItem(index) else listState.scrollToItem(index)
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .then(focus.screenModifier)
                    .safeDrawingPadding()
                    .padding(horizontal = 32.dp, vertical = 20.dp),
            // 8 dp, not 12: the bar, its labels, the search row and the count line need the height on a handheld.
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryTopBar(
                gridView = gridView,
                sort = sort,
                syncBadge = syncBadge,
                search = search,
                searchFocus = searchFocus,
                sortButton = sortButton,
                settingsButton = settings,
                onSort = { sortOpen = true },
                onOrder = { onSort(sort.copy(reverse = !sort.reverse)) },
                onToggleView = onToggleView,
                onRefresh = onRefresh,
                onOpenSettings = onOpenSettings,
                onSyncBadge = onSyncBadge,
                onSearchDown = { focusManager.moveFocus(FocusDirection.Down) },
                onSearchKeyboardClosed = {},
                onFocusArea = {},
                onSortBounds = { sortBounds = it },
            )

            banners.forEach { Banner(it) }
            if (message != null) MessageBar(message, isError)
            // The count line goes with the games, so a short screen takes its room from them, never from the hints.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasGames) {
                    Text(
                        LibraryWords.countLine(sorted.size, games.size, searching = false, sort = sort),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GooseStationTheme.colors.inkMuted,
                    )
                }

                val content = Modifier.weight(1f)
                when {
                    // While a message says what the app is doing, the list may just not be there yet.
                    !hasGames ->
                        if (message == null) {
                            Text(
                                "No games found in your games folders.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    gridView ->
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(152.dp),
                            state = gridState,
                            modifier = content,
                            // Room for the focus ring, which is drawn outside a cover.
                            contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            items(sorted, key = { it.path }) { game ->
                                val settings = gameSettingsOf(game, onGameSettings)
                                GameTile(
                                    game,
                                    onClick = { onStartGame(game) },
                                    modifier = focus.modifier(game.path).gameKeys(settings) { onSaves(game) },
                                    onLongClick = { longPress(game) },
                                )
                            }
                        }
                    else ->
                        LazyColumn(
                            state = listState,
                            modifier = content,
                            contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(sorted, key = { it.path }) { game ->
                                val settings = gameSettingsOf(game, onGameSettings)
                                GameListRow(
                                    game,
                                    onClick = { onStartGame(game) },
                                    modifier = focus.modifier(game.path).gameKeys(settings) { onSaves(game) },
                                    onLongClick = { longPress(game) },
                                )
                            }
                        }
                }
            }
            if (hasGames) {
                KeyHints(
                    listOf(
                        KeyHint(PadGlyphs.confirm, "Play"),
                        KeyHint(PadGlyphs.action, "Saves"),
                        KeyHint(PadGlyphs.options, "Game settings"),
                    ),
                )
            }
        }
        if (sortOpen) {
            LibrarySortList(
                sort,
                anchor = sortBounds.translate(-origin),
                onPick = { key ->
                    if (key != sort.key) onSort(sort.copy(key = key))
                    closeSort()
                },
                onClose = ::closeSort,
            )
        }
    }
    // Back to the tile on every way out of the choice: an open the app refuses (not ready, a game running) leaves it
    // focused, as B does.
    var refocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refocus) {
        val path = refocus ?: return@LaunchedEffect
        focus.refocus(path)
        refocus = null
    }
    choosing?.let { game ->
        GameChoicePanel(
            game,
            onSaves = {
                choosing = null
                refocus = game.path
                onSaves(game)
            },
            onGameSettings = {
                choosing = null
                refocus = game.path
                onGameSettings(game)
            },
            onClose = {
                choosing = null
                refocus = game.path
            },
        )
    }
}

/** A long press on [game]: its title, then Saves (focused) and Game settings. B closes it ([onClose]). */
@Composable
private fun GameChoicePanel(
    game: GameEntry,
    onSaves: () -> Unit,
    onGameSettings: () -> Unit,
    onClose: () -> Unit,
) {
    OverlayPanel(onBack = onClose) {
        val saves = remember { FocusRequester() }
        LaunchedEffect(game) { saves.requestFocus() }
        Text(game.title, style = MaterialTheme.typography.titleLarge, color = GooseStationTheme.colors.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Saves", onClick = onSaves, modifier = Modifier.focusRequester(saves))
            ActionButton("Game settings", onClick = onGameSettings)
        }
    }
}

/** What opens [game]'s own settings, or null for a game with no key to keep them under (a PSF). */
private fun gameSettingsOf(
    game: GameEntry,
    onGameSettings: (GameEntry) -> Unit,
): (() -> Unit)? = if (game.serial.isBlank()) null else ({ onGameSettings(game) })

/**
 * On their key-ups, X opens the game's Saves ([saves]) and Y its own settings ([settings]); without settings, Y does
 * nothing here.
 */
private fun Modifier.gameKeys(
    settings: (() -> Unit)?,
    saves: () -> Unit,
) = onKeyEvent { event ->
    val action =
        when (event.key) {
            Key.ButtonX -> saves
            Key.ButtonY -> settings
            else -> null
        } ?: return@onKeyEvent false
    if (event.type == KeyEventType.KeyUp) action()
    true
}

/**
 * Which game has focus, by path, and which one is to take it once it's composed: [pending], until anything in the
 * screen takes the focus, that game or anything else. A game scrolled away from before it was composed doesn't take
 * the focus later, when it's scrolled back in.
 */
private class GameFocus {
    var focused: String? = null
    var pending by mutableStateOf<String?>(null)

    /** Each composed game's requester, by path, to give it the focus back ([refocus]). */
    private val requesters = HashMap<String, FocusRequester>()

    /**
     * The game at [path] takes the focus again, a frame from now: after an overlay over the screen has gone, and the
     * focus has fallen where it falls as it does, which [pending] doesn't outlast.
     */
    suspend fun refocus(path: String) {
        withFrameNanos {}
        // Not composed (scrolled away): nothing to focus.
        runCatching { requesters[path]?.requestFocus() }
    }

    /** For the whole screen, which sees the focus move anywhere in it. */
    val screenModifier = Modifier.onFocusEvent { if (it.hasFocus) pending = null }

    /** For the item showing the game at [path]. */
    @Composable
    fun modifier(path: String): Modifier {
        val requester = remember { FocusRequester() }
        requesters[path] = requester
        if (pending == path) {
            LaunchedEffect(Unit) {
                // Not if something else has taken the focus since the item was composed.
                if (pending == path) {
                    requester.requestFocus()
                    pending = null
                }
            }
        }
        return Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) focused = path }
    }
}

/** The app's latest error ([isError]) or what it's doing, under a screen's top bar, as a [Notice]. */
@Composable
internal fun MessageBar(
    message: String,
    isError: Boolean,
) {
    Notice(
        status = if (isError) NoticeStatus.Danger else NoticeStatus.Info,
        icon = if (isError) null else GsIcons.sync,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = GooseStationTheme.colors.ink)
    }
}
