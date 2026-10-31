// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.Banner
import io.github.ilyalissoboi.goosestation.ui.components.BannerModel
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.theme.GooseMark
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons

/**
 * The games, as a cover grid ([gridView]) or a list, under a top bar of the goose and "Library", then Grid/List
 * ([onToggleView]), Settings ([onOpenSettings]) and Refresh ([onRefresh]). [banners] and [message] sit under the bar:
 * prompts to pick a folder again, the latest error ([isError]), or what the app is doing.
 *
 * On opening, Settings has focus when [focusSettings] (coming back from the settings), or else the game at
 * [focusPath] (the last one launched), or else the first game; with no games, Settings has it. Toggling the view keeps
 * the focused game. A, Enter or a tap call [onStartGame].
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
) {
    val settings = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val focus = remember { GameFocus() }
    val hasGames = games.isNotEmpty()
    // Only on opening: a toggle, or games coming in, focus a game as usual.
    var settingsFirst by remember { mutableStateOf(focusSettings) }

    // Not on every new list, which a resume brings: only on opening, on a toggle, and once there are games.
    LaunchedEffect(gridView, hasGames) {
        if (!hasGames || settingsFirst) {
            settingsFirst = false
            settings.requestFocus()
            return@LaunchedEffect
        }
        val index =
            listOf(focus.focused, focusPath).firstNotNullOfOrNull { path ->
                games.indexOfFirst { it.path == path }.takeIf { it >= 0 }
            } ?: 0
        // A lazy item can only take focus once it's composed: it does so itself, once the scroll brings it in.
        focus.pending = games[index].path
        if (gridView) gridState.scrollToItem(index) else listState.scrollToItem(index)
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(focus.screenModifier)
                .safeDrawingPadding()
                .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The buttons go under the name when they don't fit beside it, on a narrow screen or with large text.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GooseMark(Modifier.size(36.dp))
                Text(
                    "Library",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    if (gridView) "List" else "Grid",
                    onClick = onToggleView,
                    icon = if (gridView) GsIcons.list else GsIcons.grid,
                )
                ActionButton(
                    "Settings",
                    onClick = onOpenSettings,
                    modifier = Modifier.focusRequester(settings),
                    icon = GsIcons.settings,
                )
                ActionButton("Refresh", onClick = onRefresh, icon = GsIcons.sync)
            }
        }

        banners.forEach { Banner(it) }
        if (message != null) MessageBar(message, isError)

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
                    items(games, key = { it.path }) { game ->
                        GameTile(game, onClick = { onStartGame(game) }, modifier = focus.modifier(game.path))
                    }
                }
            else ->
                LazyColumn(
                    state = listState,
                    modifier = content,
                    contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(games, key = { it.path }) { game ->
                        GameListRow(game, onClick = { onStartGame(game) }, modifier = focus.modifier(game.path))
                    }
                }
        }
    }
}

/**
 * Which game has focus, by path, and which one is to take it once it's composed: [pending], until anything in the
 * screen takes the focus, that game or anything else. A game scrolled away from before it was composed doesn't take
 * the focus later, when it's scrolled back in.
 */
private class GameFocus {
    var focused: String? = null
    var pending by mutableStateOf<String?>(null)

    /** For the whole screen, which sees the focus move anywhere in it. */
    val screenModifier = Modifier.onFocusEvent { if (it.hasFocus) pending = null }

    /** For the item showing the game at [path]. */
    @Composable
    fun modifier(path: String): Modifier {
        val requester = remember { FocusRequester() }
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
