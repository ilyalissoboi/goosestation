// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.components.RailItem
import io.github.ilyalissoboi.goosestation.ui.components.backKeys
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import kotlinx.coroutines.launch

/** The categories' rail: the design system's `rail-width`. */
private val RAIL_WIDTH = 232.dp

/** Said at the rail's foot until [SettingsPanels]' `coreReady`, when only Folders can be picked. */
const val NEEDS_CORE_RAIL = "The other settings are available once GooseStation has started."

/** The test tags of [SettingsPanels]' two panels. */
const val SETTINGS_RAIL = "settings-rail"
const val SETTINGS_PAGE = "settings-page"

/**
 * The settings in two panels: the categories down the left, in a rail ([RailItem]s under "Settings"), and the
 * [current] one's [page] beside them, which is given where its Back goes. The current category is marked in the rail.
 *
 * **The rail.** Up and Down move through the categories, and each shows its page as the focus reaches it
 * ([onShow]), so the page beside the rail is always the focused category's. A, Enter, Right or a tap go into the
 * page ([onEnterPage]), on its first row. B or Back go back to the library ([onBack]). Until [coreReady], only
 * Folders can be picked: the others are dimmed, and a line at the rail's foot says why. While [locked] (a data folder
 * change under way), none can, and the focus stays in the page.
 *
 * **The page.** It works as it does alone. B or Back from it, once it has no step of its own to go back, give the
 * focus back to its category; so does Left, where nothing in the page takes it. Up, Down and Right stop at its
 * edges. A touch in the page counts as going into it.
 *
 * As they open, the focus is on the current category, or in the page with [inPage] (coming back to it from a screen
 * it opened). Until then, and while the focus is on the rail, the page can't take it: a page asks for the focus as
 * it's shown, and again as its settings are read.
 */
@Composable
fun SettingsPanels(
    current: SettingsEntry?,
    inPage: Boolean,
    coreReady: Boolean,
    locked: Boolean,
    onShow: (SettingsEntry) -> Unit,
    onEnterPage: () -> Unit,
    onBack: () -> Unit,
    page: @Composable (onBack: () -> Unit) -> Unit,
) {
    val colors = GooseStationTheme.colors
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val categories = remember { SettingsEntry.entries.associateWith { FocusRequester() } }
    val pageFocus = remember { FocusRequester() }
    // Read by the focus and key handlers, which run between compositions: set as a handler acts, then by the caller.
    val now = remember { PanelsNow(current, inPage) }
    SideEffect {
        now.current = current
        now.pageOpen = inPage
    }
    var pageHasFocus by remember { mutableStateOf(false) }

    fun enabled(entry: SettingsEntry) = !locked && (coreReady || !entry.needsCore)

    fun show(entry: SettingsEntry) {
        now.current = entry
        now.pageOpen = false
        onShow(entry)
    }

    fun toPage() {
        now.pageOpen = true
        onEnterPage()
        pageFocus.requestFocus()
    }

    // Refused while something in the page keeps the focus (a question over it), or the rail is locked.
    fun toCategories() {
        val entry = now.current ?: return
        if (categories.getValue(entry).requestFocus()) show(entry)
    }

    // The category [by] places from [from], past any that can't be picked; the page first, so the focus finds it
    // the current one.
    fun step(
        from: SettingsEntry,
        by: Int,
    ) {
        val all = SettingsEntry.entries
        var index = all.indexOf(from) + by
        while (index in all.indices && !enabled(all[index])) index += by
        val next = all.getOrNull(index) ?: return
        show(next)
        categories.getValue(next).requestFocus()
    }

    // The rail's items, a category each.
    @Composable
    fun Categories() {
        SettingsEntry.entries.forEach { entry ->
            RailItem(
                entry.label,
                entry.icon,
                current = entry == current,
                onClick = {
                    if (entry == now.current) {
                        toPage()
                    } else {
                        // A tap on another category: its page takes the focus as it's shown.
                        show(entry)
                        now.pageOpen = true
                        onEnterPage()
                    }
                },
                modifier =
                    Modifier
                        .focusRequester(categories.getValue(entry))
                        // Before the focus ring, so the keys bubble up to it from the focused item. Both
                        // halves of the press are taken, so neither moves the focus by itself.
                        .onKeyEvent { event ->
                            val down = event.type == KeyEventType.KeyDown
                            when (event.key) {
                                Key.DirectionUp -> if (down) step(entry, -1)
                                Key.DirectionDown -> if (down) step(entry, 1)
                                Key.DirectionRight -> if (down) toPage()
                                Key.DirectionLeft -> {}
                                else -> return@onKeyEvent false
                            }
                            true
                        },
                enabled = enabled(entry),
                onFocusChange = { focused ->
                    // Focus that came by itself (what had it in the page went away, and Compose gave
                    // it to the first thing that takes it): back where it belongs, once the frame has
                    // it. The rail's own moves have closed the page by then, and are on its category.
                    if (focused && (now.pageOpen || entry != now.current)) {
                        scope.launch {
                            when {
                                now.pageOpen -> if (!pageHasFocus) pageFocus.requestFocus()
                                entry != now.current ->
                                    now.current?.let { categories.getValue(it).requestFocus() }
                            }
                        }
                    }
                },
            )
        }
    }

    // The system's Back goes to the page's own handlers first, which end in this: from the page to its category,
    // and from the rail (or a page only touched) to the library.
    val pageBack = { if (pageHasFocus) toCategories() else onBack() }

    BackHandler(onBack = onBack)
    LaunchedEffect(current != null) {
        val entry = current ?: return@LaunchedEffect
        if (inPage) pageFocus.requestFocus() else categories.getValue(entry).requestFocus()
    }
    // The rail can't hold the focus while it's locked.
    LaunchedEffect(locked) { if (locked && !pageHasFocus) toPage() }

    Row(Modifier.fillMaxSize().background(colors.ground)) {
        Box(Modifier.fillMaxHeight().background(colors.surface)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
                        ).width(RAIL_WIDTH)
                        .testTag(SETTINGS_RAIL)
                        .backKeys { onBack() }
                        .focusGroup(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            // The page's own, down; the ring's room and the items' own 12 dp, across.
                            .padding(horizontal = 12.dp, vertical = 20.dp),
                    // The focus rings of two items, one over the other.
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.ink,
                        modifier =
                            Modifier
                                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                                // A page's title's line, so the two start level.
                                .heightIn(min = 32.dp)
                                .wrapContentHeight()
                                .semantics { heading() },
                    )
                    Categories()
                }
                // At the rail's foot, where it shows whichever category the rail has scrolled to.
                if (!coreReady) {
                    Text(
                        NEEDS_CORE_RAIL,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
                    )
                }
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(colors.line))
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag(SETTINGS_PAGE)
                    // The rail has the window's start edge; the page pads the others itself.
                    .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                    // Before the page sees it, and without taking it: a touch in the page goes into it, so what
                    // it then focuses (a text field) can.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press && !now.pageOpen) {
                                    now.pageOpen = true
                                    onEnterPage()
                                }
                            }
                        }
                    }.onFocusChanged { pageHasFocus = it.hasFocus }
                    // After the page's rows: Left that none of them takes, and that finds nothing in the page,
                    // goes back to the categories.
                    .onKeyEvent { event ->
                        if (event.key != Key.DirectionLeft) return@onKeyEvent false
                        if (event.type == KeyEventType.KeyDown && !focusManager.moveFocus(FocusDirection.Left)) {
                            toCategories()
                        }
                        true
                    }.focusRequester(pageFocus)
                    .focusProperties {
                        // Asked for (FocusDirection.Enter), the focus comes in only once the page is gone into,
                        // and goes back to the rail; the D-pad neither leaves the page nor wanders into it.
                        onEnter = {
                            if (!now.pageOpen || requestedFocusDirection != FocusDirection.Enter) {
                                cancelFocusChange()
                            }
                        }
                        onExit = { if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() }
                    }.focusGroup(),
        ) {
            if (current != null) key(current) { page(pageBack) }
        }
    }
}

/** Where [SettingsPanels] stands, as its handlers last left it: the category shown, and whether its page is gone into. */
private class PanelsNow(
    var current: SettingsEntry?,
    var pageOpen: Boolean,
)
