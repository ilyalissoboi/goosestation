// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.SyncBadge
import io.github.ilyalissoboi.goosestation.library.LibrarySort
import io.github.ilyalissoboi.goosestation.library.LibraryWords
import io.github.ilyalissoboi.goosestation.ui.components.ActionLabelStrip
import io.github.ilyalissoboi.goosestation.ui.components.IconActionButton
import io.github.ilyalissoboi.goosestation.ui.components.SearchField
import io.github.ilyalissoboi.goosestation.ui.components.SearchFocus
import io.github.ilyalissoboi.goosestation.ui.components.SyncStatusBadge
import io.github.ilyalissoboi.goosestation.ui.components.rememberActionLabelHost
import io.github.ilyalissoboi.goosestation.ui.theme.GooseMark
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import kotlin.math.max

/** The room between the bar's parts. */
private val GAP = 8.dp

/**
 * The least width the search field takes beside the title and buttons, at text 1.0×, growing with the text it holds;
 * with less, it goes under them.
 */
private val SEARCH_MIN = 144.dp

/** How the library's top bar lays out its title, search field, sync badge and buttons. */
enum class BarLayout {
    /** All on one row, the field taking what's left. */
    OneRow,

    /** The title, badge and buttons on one row, the field under them. */
    SearchBelow,

    /** The title (with the badge, if it fits), then the buttons, then the field: a phone in portrait. */
    Stacked,
}

/**
 * Which [BarLayout] a bar [width] wide takes, for a [title] and [trailing] (the badge and buttons) that wide, with
 * text at [fontScale].
 */
fun barLayout(
    width: Dp,
    title: Dp,
    trailing: Dp,
    fontScale: Float = 1f,
): BarLayout =
    when {
        title + GAP + SEARCH_MIN * fontScale + GAP + trailing <= width -> BarLayout.OneRow
        title + GAP + trailing <= width -> BarLayout.SearchBelow
        else -> BarLayout.Stacked
    }

/** Where the focus is in the library, for its button hints. */
enum class LibraryArea { Game, Bar, Search, ClearSearch, Banner, Update }

/**
 * The library's top bar: the goose and "Library", the [search] field, the sync badge while sync is on ([syncBadge];
 * [onSyncBadge]), then icon buttons for Sort ([onSort]), Order ([onOrder]), the view ([onToggleView]: a bulleted list
 * in grid mode, the grid in list mode), Refresh ([onRefresh]) and Settings ([onOpenSettings]). The focused button's
 * label shows straight under the buttons; where the bar has no room for the field, the field goes under that. Down
 * from the field calls [onSearchDown]; its keyboard closing, [onSearchKeyboardClosed]. [onFocusArea] hears the focus
 * come into the bar or the field. [onSortBounds] hears where Sort is, for its list to open under it.
 */
@Composable
fun LibraryTopBar(
    gridView: Boolean,
    sort: LibrarySort,
    syncBadge: SyncBadge?,
    search: TextFieldState,
    searchFocus: SearchFocus,
    sortButton: FocusRequester,
    settingsButton: FocusRequester,
    onSort: () -> Unit,
    onOrder: () -> Unit,
    onToggleView: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onSyncBadge: () -> Unit,
    onSearchDown: () -> Unit,
    onSearchKeyboardClosed: () -> Unit,
    onFocusArea: (LibraryArea) -> Unit,
    modifier: Modifier = Modifier,
    onSortBounds: (Rect) -> Unit = {},
) {
    val labels = rememberActionLabelHost()
    val bar = { focused: Boolean -> if (focused) onFocusArea(LibraryArea.Bar) }
    Layout(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GooseMark(Modifier.size(36.dp))
                Text(
                    "Library",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            SearchField(
                search,
                searchFocus,
                onKeyboardClosed = onSearchKeyboardClosed,
                onDown = onSearchDown,
                onFocusChange = { if (it) onFocusArea(LibraryArea.Search) },
            )
            Box(Modifier.onFocusChanged { bar(it.hasFocus) }) {
                syncBadge?.let { SyncStatusBadge(it, onClick = onSyncBadge) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                IconActionButton(
                    GsIcons.sort,
                    "Sort: ${LibraryWords.sortName(sort.key)}",
                    onClick = onSort,
                    labels = labels,
                    modifier =
                        Modifier
                            .focusRequester(sortButton)
                            .onGloballyPositioned { onSortBounds(it.boundsInRoot()) },
                    onFocusChange = bar,
                )
                IconActionButton(
                    if (sort.reverse) GsIcons.orderReversed else GsIcons.order,
                    "Order: ${LibraryWords.direction(sort)}",
                    onClick = onOrder,
                    labels = labels,
                    onFocusChange = bar,
                )
                IconActionButton(
                    if (gridView) GsIcons.listBulleted else GsIcons.grid,
                    if (gridView) "Show as a list" else "Show as a grid",
                    onClick = onToggleView,
                    labels = labels,
                    onFocusChange = bar,
                )
                IconActionButton(
                    GsIcons.refresh,
                    "Refresh the library",
                    onClick = onRefresh,
                    labels = labels,
                    onFocusChange = bar,
                )
                IconActionButton(
                    GsIcons.settings,
                    "Settings",
                    onClick = onOpenSettings,
                    labels = labels,
                    modifier = Modifier.focusRequester(settingsButton),
                    onFocusChange = bar,
                )
            }
            ActionLabelStrip(labels)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val (titleM, searchM, badgeM, buttonsM, stripM) = measurables
        val width = constraints.maxWidth
        val gap = GAP.roundToPx()
        val row = 48.dp.roundToPx()
        val loose = Constraints(maxWidth = width)
        val title = titleM.measure(loose)
        val badge = badgeM.measure(loose)
        val buttons = buttonsM.measure(loose)
        val trailing = (if (badge.width > 0) badge.width + gap else 0) + buttons.width
        val layout = barLayout(width.toDp(), title.width.toDp(), trailing.toDp(), fontScale)
        val strip = stripM.measure(Constraints.fixedWidth(width))

        fun Placeable.PlacementScope.centred(
            placeable: Placeable,
            x: Int,
            top: Int,
            height: Int,
        ) = placeable.place(x, top + (height - placeable.height) / 2)

        when (layout) {
            BarLayout.OneRow -> {
                val field = searchM.measure(Constraints.fixedWidth(width - title.width - trailing - 2 * gap))
                val height = maxOf(row, title.height, field.height, badge.height, buttons.height)
                layout(width, height + strip.height) {
                    centred(title, 0, 0, height)
                    centred(field, title.width + gap, 0, height)
                    centred(badge, width - trailing, 0, height)
                    centred(buttons, width - buttons.width, 0, height)
                    strip.place(0, height)
                }
            }
            BarLayout.SearchBelow -> {
                val field = searchM.measure(Constraints.fixedWidth(width))
                val height = maxOf(row, title.height, badge.height, buttons.height)
                layout(width, height + strip.height + field.height) {
                    centred(title, 0, 0, height)
                    centred(badge, width - trailing, 0, height)
                    centred(buttons, width - buttons.width, 0, height)
                    strip.place(0, height)
                    field.place(0, height + strip.height)
                }
            }
            BarLayout.Stacked -> {
                val field = searchM.measure(Constraints.fixedWidth(width))
                val badgeBesideTitle = badge.width > 0 && title.width + gap + badge.width <= width
                val first = max(row, title.height)
                val badgeRow = if (badge.width > 0 && !badgeBesideTitle) badge.height + gap else 0
                val second = max(row, buttons.height)
                val top = first + gap + badgeRow
                layout(width, top + second + strip.height + field.height) {
                    centred(title, 0, 0, first)
                    if (badgeBesideTitle) centred(badge, width - badge.width, 0, first) else badge.place(0, first + gap)
                    centred(buttons, width - buttons.width, top, second)
                    strip.place(0, top + second)
                    field.place(0, top + second + strip.height)
                }
            }
        }
    }
}
