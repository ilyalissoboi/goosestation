// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.SaveLists
import io.github.ilyalissoboi.goosestation.app.SavesActions
import io.github.ilyalissoboi.goosestation.app.SavesPageState
import io.github.ilyalissoboi.goosestation.app.SlotEntry
import io.github.ilyalissoboi.goosestation.app.SlotTab
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.Eyebrow
import io.github.ilyalissoboi.goosestation.ui.components.KeyHint
import io.github.ilyalissoboi.goosestation.ui.components.KeyHints
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.PanelTabs
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import java.time.ZonedDateTime

/**
 * A game's Saves page, from its library tile, in the app's window: the [page]'s game as an [Eyebrow] over "Saves", then
 * its two tabs ([PanelTabs], L1 and R1), This game (the resume state, the slots, and Other saves) and Shared, with the
 * in-game Load panel's rows, as seen from [now]. The page scrolls as one, [KeyHints] fixed at its foot. A on a save
 * starts the game from it ([SavesActions.pick]); X or a long press ask, in an [OverlayPanel], before deleting it. The
 * first save, or the row after a deleted one, is focused; with none, the title holds the focus. B and Back go back
 * ([onBack]).
 */
@Composable
fun SavesScreen(
    page: SavesPageState,
    actions: SavesActions,
    now: ZonedDateTime,
    onBack: () -> Unit,
) {
    val colors = GooseStationTheme.colors
    val sharedTab = page.tab == SlotTab.Shared
    val entries =
        when {
            sharedTab -> page.shared
            page.hasKey -> page.entries
            else -> emptyList()
        }
    val others = if (sharedTab || !page.hasKey) emptyList() else page.others
    val title = remember { FocusRequester() }
    val rows = remember { HashMap<Triple<Boolean, String?, Int>, FocusRequester>() }

    fun row(entry: SlotEntry) = rows.getOrPut(Triple(entry.shared, entry.owner, entry.slot)) { FocusRequester() }

    BackHandler(onBack = onBack)
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // Each switch brings the focus to the new tab's rows.
    var switches by remember { mutableIntStateOf(0) }
    val switchTab = {
        actions.switchTab()
        switches++
    }
    val listed = entries.orEmpty() + others
    var titleFocused by remember { mutableStateOf(false) }

    LaunchedEffect(entries == null, page.tab, page.deleting, page.focusRow, switches) {
        if (page.deleting != null) return@LaunchedEffect
        val target =
            page.focusRow?.let { focus -> listed.firstOrNull { SaveLists.sameRow(it, focus) } } ?: listed.firstOrNull()
        (target?.let { row(it) } ?: title).requestFocus()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding()
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            // B, and a Back key reaching Compose, go to the back handlers.
                            Key.Back, Key.ButtonB -> {
                                if (event.type == KeyEventType.KeyUp) dispatcher?.onBackPressed() ?: onBack()
                                true
                            }
                            Key.ButtonL1, Key.ButtonR1 -> {
                                if (event.type == KeyEventType.KeyUp) switchTab()
                                true
                            }
                            else -> false
                        }
                    }.padding(horizontal = 32.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val short = maxHeight < SHORT_SLOT_LIST
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Eyebrow(page.game.title)
                    Text(
                        "Saves",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.ink,
                        // Focusable while there's no save to focus, so B and the tabs still reach the page; it stays
                        // so while it has the focus, until a row takes it.
                        modifier =
                            Modifier
                                .focusRequester(title)
                                .onFocusChanged { titleFocused = it.isFocused }
                                .focusable(enabled = listed.isEmpty() || titleFocused),
                    )
                    PanelTabs(
                        listOf("This game", "Shared"),
                        current = if (sharedTab) 1 else 0,
                        onSelect = { if (it != page.tab.ordinal) switchTab() },
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    page.error?.let { error ->
                        Notice(NoticeStatus.Danger) {
                            Text(error, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
                        }
                    }
                    when {
                        !sharedTab && !page.hasKey -> SlotLine(NO_KEY_STATES)
                        entries != null && entries.isEmpty() && others.isEmpty() ->
                            SlotLine(if (sharedTab) "No shared saves yet." else "No saves yet for this game.")
                    }
                    entries?.forEach { entry ->
                        SlotRow(
                            entry,
                            now,
                            enabled = true,
                            onClick = { actions.pick(entry) },
                            modifier = Modifier.focusRequester(row(entry)),
                            short = short,
                            onDelete = { actions.askDelete(entry) },
                        )
                    }
                    if (others.isNotEmpty()) {
                        Eyebrow("Other saves", Modifier.padding(top = 12.dp, start = 8.dp))
                        others.forEach { entry ->
                            SlotRow(
                                entry,
                                now,
                                enabled = true,
                                onClick = { actions.pick(entry) },
                                modifier = Modifier.focusRequester(row(entry)),
                                short = short,
                                onDelete = { actions.askDelete(entry) },
                            )
                        }
                    }
                }
            }
            KeyHints(
                listOf(
                    KeyHint(PadGlyphs.confirm, "Start"),
                    KeyHint(PadGlyphs.action, "Delete"),
                    KeyHint("${PadGlyphs.previousTab} ${PadGlyphs.nextTab}", "Switch"),
                    KeyHint(PadGlyphs.back, "Back"),
                ),
            )
        }
        page.deleting?.let { SavesDeleteQuestion(it, actions::confirmDelete, actions::cancelDelete) }
    }
}

/** The question before deleting [entry]'s save, over the Saves page: Delete ([onConfirm]), then Cancel, focused. */
@Composable
internal fun SavesDeleteQuestion(
    entry: SlotEntry,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    OverlayPanel(onBack = onCancel) {
        val cancel = remember { FocusRequester() }
        LaunchedEffect(entry) { cancel.requestFocus() }
        Text(
            SaveLists.deleteQuestion(entry),
            style = MaterialTheme.typography.bodyLarge,
            color = GooseStationTheme.colors.ink,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton("Delete", onClick = onConfirm, kind = ButtonKind.Danger)
            ActionButton(
                "Cancel",
                onClick = onCancel,
                modifier = Modifier.focusRequester(cancel),
                kind = ButtonKind.Ghost,
            )
        }
    }
}
