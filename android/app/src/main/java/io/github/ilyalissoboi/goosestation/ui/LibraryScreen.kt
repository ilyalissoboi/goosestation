// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.SyncBadge
import io.github.ilyalissoboi.goosestation.app.UpdateState
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.library.LibraryOrder
import io.github.ilyalissoboi.goosestation.library.LibrarySearch
import io.github.ilyalissoboi.goosestation.library.LibrarySort
import io.github.ilyalissoboi.goosestation.library.LibraryWords
import io.github.ilyalissoboi.goosestation.library.SearchIndex
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
import io.github.ilyalissoboi.goosestation.update.AppVersion
import kotlinx.coroutines.launch

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
 * game without a key goes straight to its Saves). With games, the foot says so ([KeyHints]). While [overlayOpen],
 * something the caller draws over the library has the focus (the resume prompt); once it closes, the game last
 * focused takes the focus back, not the first thing on the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    overlayOpen: Boolean = false,
    update: UpdateBannerModel? = null,
) {
    val settings = remember { FocusRequester() }
    val sortButton = remember { FocusRequester() }
    val searchFocus = rememberSearchFocus()
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val focus = remember { GameFocus() }
    val hasGames = games.isNotEmpty()
    // Only on opening: a toggle, or games coming in, focus a game as usual.
    var settingsFirst by remember { mutableStateOf(focusSettings) }
    // The game whose long press asks: Saves or Game settings.
    var choosing by remember { mutableStateOf<GameEntry?>(null) }
    // The game to give the focus back to, once what took it (a panel, the update banner) has gone.
    var refocus by remember { mutableStateOf<String?>(null) }
    // What's new, over the library; closed, the focus goes back to its button.
    var whatsNewOpen by remember { mutableStateOf(false) }
    val whatsNewButton = remember { FocusRequester() }
    var whatsNewClosed by remember { mutableStateOf(false) }
    // Once the panel has gone: while it's there, it keeps the focus from leaving it.
    LaunchedEffect(whatsNewOpen, whatsNewClosed) {
        if (!whatsNewOpen && whatsNewClosed) {
            whatsNewClosed = false
            whatsNewButton.requestFocus()
        }
    }

    fun longPress(game: GameEntry) = if (game.serial.isBlank()) onSaves(game) else choosing = game

    val sorted = remember(games, sort) { LibraryOrder.sorted(games, sort) }
    val query = LibrarySearch.query(search.text)
    // A new list's file names are decoded once, not on each keystroke.
    val index = remember(sorted) { SearchIndex(sorted) }
    val shown = remember(index, query) { LibrarySearch.filter(index, query) }
    // A search that matches none of the games: the screen says so, with Clear search. Not without games, where there's
    // nothing to clear the search for and no button to focus.
    val noMatch = hasGames && shown.isEmpty()
    // Where the focus is, for the button hints.
    var area by remember { mutableStateOf(LibraryArea.Game) }
    val clearSearch = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible

    suspend fun scrollTo(index: Int) = if (gridView) gridState.scrollToItem(index) else listState.scrollToItem(index)

    fun inView(index: Int) =
        if (gridView) {
            gridState.layoutInfo.visibleItemsInfo.any { it.index == index }
        } else {
            listState.layoutInfo.visibleItemsInfo.any { it.index == index }
        }

    fun clear() {
        search.clearText()
        // Clear search goes with the search: the field takes the focus, as the button gives it.
        if (area == LibraryArea.ClearSearch) searchFocus.focus()
    }
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

    // As the games shown change (a sort, a search, a new list), the focus never jumps to the top: a focused game that's
    // still shown stays in view; a search typed or changed shows its matches from the first; otherwise the game last
    // focused stays in view, so Down from the bar reaches it wherever it moved. Not on opening, which focuses a game of
    // its own.
    val before = remember { ShownBefore(shown, query) }
    LaunchedEffect(shown) {
        if (shown === before.games) return@LaunchedEffect
        val queryChanged = query != before.query
        before.games = shown
        before.query = query
        val last = shown.indexOfFirst { it.path == focus.focused }.takeIf { it >= 0 }
        when {
            area == LibraryArea.Game && last != null -> if (!inView(last)) scrollTo(last)
            query != null && queryChanged -> scrollTo(0)
            last != null -> if (!inView(last)) scrollTo(last)
        }
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
                shown.indexOfFirst { it.path == path }.takeIf { it >= 0 }
            } ?: 0
        // Every game filtered out: the search has the focus, to change or clear it.
        if (shown.isEmpty()) {
            searchFocus.focus()
            return@LaunchedEffect
        }
        // A lazy item can only take focus once it's composed: it does so itself, once the scroll brings it in.
        focus.pending = shown[index].path
        if (gridView) gridState.scrollToItem(index) else listState.scrollToItem(index)
    }

    @Composable
    fun gameModifier(
        game: GameEntry,
        settings: (() -> Unit)?,
    ) = focus
        .modifier(game.path)
        .onFocusChanged { if (it.isFocused) area = LibraryArea.Game }
        .gameKeys(settings) { onSaves(game) }

    // Up and Down in the grid go to the game in the same column of the row above or below (the last game of a shorter
    // last row), rather than where Compose's search lands when that row isn't composed yet: its first game. A row
    // that isn't composed is scrolled in just far enough to be, and the game takes the focus once it is; the grid's
    // centring then brings it the rest of the way. Up from the first row goes on to the top bar, as before.
    fun Modifier.gridRows(index: Int) =
        onPreviewKeyEvent { event ->
            val step =
                when (event.key) {
                    Key.DirectionDown -> 1
                    Key.DirectionUp -> -1
                    else -> return@onPreviewKeyEvent false
                }
            val info = gridState.layoutInfo
            val columns = (info.visibleItemsInfo.maxOfOrNull { it.column } ?: return@onPreviewKeyEvent false) + 1
            val row = index / columns + step
            if (row < 0 || row > shown.lastIndex / columns) return@onPreviewKeyEvent false
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
            val target = minOf(index + step * columns, shown.lastIndex)
            val path = shown[target].path
            if (info.visibleItemsInfo.any { it.index == target } && focus.request(path)) return@onPreviewKeyEvent true
            focus.pending = path
            scope.launch {
                val visible = gridState.layoutInfo.visibleItemsInfo
                val distance =
                    if (step > 0) {
                        val bottom = visible.maxOf { it.offset.y + it.size.height }
                        bottom + info.mainAxisItemSpacing - info.viewportEndOffset + 1
                    } else {
                        val top = visible.minOf { it.offset.y }
                        top - info.mainAxisItemSpacing - info.viewportStartOffset - 1
                    }
                gridState.scrollBy(distance.toFloat())
            }
            true
        }

    // A search Back clears, as B does, while the sort list or the long press's choice isn't taking Back first.
    BackHandler(enabled = query != null && !sortOpen && choosing == null && !whatsNewOpen) { clear() }

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .then(focus.screenModifier)
                    .onKeyEvent { event ->
                        when (event.key) {
                            // From anywhere in the library: the search, with the keyboard.
                            Key.ButtonSelect -> {
                                if (event.type == KeyEventType.KeyUp) searchFocus.focusAndType()
                                true
                            }
                            // The keyboard first, then the search; with neither, Back is the activity's.
                            Key.ButtonB, Key.Back ->
                                when {
                                    imeVisible -> true.also { if (event.type == KeyEventType.KeyUp) keyboard?.hide() }
                                    query != null -> true.also { if (event.type == KeyEventType.KeyUp) clear() }
                                    else -> false
                                }
                            else -> false
                        }
                    }.safeDrawingPadding()
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
                onSearchDown = {
                    if (shown.isEmpty()) {
                        if (noMatch) clearSearch.requestFocus()
                    } else {
                        scope.launch {
                            scrollTo(0)
                            focus.refocus(shown[0].path)
                        }
                    }
                },
                // Clear search takes the focus only once the keyboard has closed on nothing matching, never as the
                // typing passes through a search that matches nothing.
                onSearchKeyboardClosed = { if (noMatch) clearSearch.requestFocus() },
                onFocusArea = { area = it },
                onSortBounds = { sortBounds = it },
            )

            banners.forEach {
                Banner(it, Modifier.onFocusChanged { state -> if (state.hasFocus) area = LibraryArea.Banner })
            }
            update?.let {
                // Hide and Skip take the banner away, focus and all: the focus goes back to the game last focused.
                fun back() {
                    refocus = focus.focused ?: shown.firstOrNull()?.path
                }
                UpdateBanner(
                    it.copy(
                        onHide = {
                            back()
                            it.onHide()
                        },
                        onSkip = {
                            back()
                            it.onSkip()
                        },
                    ),
                    onWhatsNew = { whatsNewOpen = true },
                    modifier = Modifier.onFocusChanged { state -> if (state.hasFocus) area = LibraryArea.Update },
                    whatsNewFocus = whatsNewButton,
                )
            }
            if (message != null) MessageBar(message, isError)
            // The count line goes with the games, so a short screen takes its room from them, never from the hints.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasGames) {
                    Text(
                        LibraryWords.countLine(shown.size, games.size, searching = query != null, sort = sort),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GooseStationTheme.colors.inkMuted,
                    )
                }

                val content = Modifier.weight(1f)
                when {
                    // While a message says what the app is doing, the list may just not be there yet.
                    noMatch ->
                        NoMatch(
                            query.orEmpty(),
                            // Scrolls on a short screen, so Clear search comes into view as it takes the focus.
                            area = content.verticalScroll(rememberScrollState()),
                            onClear = ::clear,
                            modifier =
                                Modifier
                                    .focusRequester(clearSearch)
                                    .onFocusChanged { if (it.isFocused) area = LibraryArea.ClearSearch },
                        )
                    !hasGames ->
                        if (message == null) {
                            Text(
                                "No games found in your games folders.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    gridView ->
                        BoxWithConstraints(content) {
                            // A row's room: the grid's height less its padding, so a whole tile fits on a short screen.
                            val rowHeight = maxHeight - GRID_TOP - GRID_BOTTOM
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(152.dp),
                                state = gridState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding =
                                    PaddingValues(start = 4.dp, top = GRID_TOP, end = 4.dp, bottom = GRID_BOTTOM),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                itemsIndexed(shown, key = { _, game -> game.path }) { index, game ->
                                    val settings = gameSettingsOf(game, onGameSettings)
                                    GameTile(
                                        game,
                                        onClick = { onStartGame(game) },
                                        modifier = gameModifier(game, settings).gridRows(index),
                                        onLongClick = { longPress(game) },
                                        query = query,
                                        fitHeight = rowHeight,
                                    )
                                }
                            }
                        }
                    else ->
                        LazyColumn(
                            state = listState,
                            modifier = content,
                            contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(shown, key = { it.path }) { game ->
                                val settings = gameSettingsOf(game, onGameSettings)
                                GameListRow(
                                    game,
                                    onClick = { onStartGame(game) },
                                    modifier = gameModifier(game, settings),
                                    onLongClick = { longPress(game) },
                                    query = query,
                                )
                            }
                        }
                }
            }
            if (hasGames) {
                val hideable = update?.state.let { it is UpdateState.Available || it is UpdateState.Failed }
                KeyHints(libraryHints(area, sortOpen, searching = query != null, updateHideable = hideable))
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
    // Back to the tile on every way out of the choice (an open the app refuses, not ready or a game running, leaves it
    // focused, as B does), and once an overlay of the caller's closes, as the long press's own does.
    var overlayWasOpen by remember { mutableStateOf(overlayOpen) }
    LaunchedEffect(overlayOpen) {
        if (overlayWasOpen && !overlayOpen) refocus = focus.focused
        overlayWasOpen = overlayOpen
    }
    LaunchedEffect(refocus) {
        val path = refocus ?: return@LaunchedEffect
        focus.refocus(path)
        refocus = null
    }
    if (whatsNewOpen && update != null) {
        val version = update.state.let { (it as? UpdateState.Available)?.release?.versionName.orEmpty() }
        WhatsNewPanel(AppVersion.short(version), update.loadChanges) {
            whatsNewOpen = false
            whatsNewClosed = true
        }
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

/**
 * The grid's padding above and below its tiles: room for the focus ring, which is drawn outside a cover, and under the
 * last row, so the button hints don't crowd it (24 dp from a tile to the hints, with the screen's gap).
 */
private val GRID_TOP = 4.dp
private val GRID_BOTTOM = 16.dp

/** What the library showed last, so a change of it can be told from the first time. */
private class ShownBefore(
    var games: List<GameEntry>,
    var query: String?,
)

/** The library's button hints, for where the focus is ([area]), the sort list being open, and a search. */
private fun libraryHints(
    area: LibraryArea,
    sortOpen: Boolean,
    searching: Boolean,
    updateHideable: Boolean = false,
): List<KeyHint> {
    if (sortOpen) return listOf(KeyHint(PadGlyphs.confirm, "Choose"), KeyHint(PadGlyphs.back, "Close"))
    val hints =
        when (area) {
            LibraryArea.Game ->
                listOf(
                    KeyHint(PadGlyphs.confirm, "Play"),
                    KeyHint(PadGlyphs.action, "Saves"),
                    KeyHint(PadGlyphs.options, "Game settings"),
                )
            LibraryArea.Bar, LibraryArea.ClearSearch, LibraryArea.Banner -> listOf(KeyHint(PadGlyphs.confirm, "Choose"))
            LibraryArea.Update ->
                listOfNotNull(
                    KeyHint(PadGlyphs.confirm, "Choose"),
                    KeyHint(PadGlyphs.back, "Hide").takeIf { updateHideable },
                )
            LibraryArea.Search -> listOf(KeyHint(PadGlyphs.confirm, "Type"))
        }
    // While searching, Clear search takes Search's place, as the mockup has it, so the hints keep to one line on a
    // handheld; Select still jumps to the field.
    return when {
        searching -> hints + KeyHint(PadGlyphs.back, "Clear search")
        area == LibraryArea.Search -> hints
        else -> hints + KeyHint(PadGlyphs.select, "Search")
    }
}

/** Nothing matches the search's [query]: it says so, with Clear search ([onClear]), in the games' [area]. */
@Composable
private fun NoMatch(
    query: String,
    onClear: () -> Unit,
    area: Modifier,
    modifier: Modifier = Modifier,
) {
    // Room for the button's focus ring.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = area.padding(4.dp)) {
        Text(
            "No games match “$query”.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionButton("Clear search", onClick = onClear, modifier = modifier)
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

    /** The composed game at [path] takes the focus now; false if it isn't composed. */
    fun request(path: String): Boolean = runCatching { requesters[path]?.requestFocus() == true }.getOrDefault(false)

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
