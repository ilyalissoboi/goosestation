// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.SaveListState
import io.github.ilyalissoboi.goosestation.app.SlotEntry
import io.github.ilyalissoboi.goosestation.app.SlotMode
import io.github.ilyalissoboi.goosestation.app.SlotTab
import io.github.ilyalissoboi.goosestation.ui.components.Eyebrow
import java.time.ZonedDateTime

/** The save rows' focus requesters, one per row (its list, owner and slot), kept across reads of the lists. */
@Stable
class SaveRowFocus {
    private val rows = HashMap<Triple<Boolean, String?, Int>, FocusRequester>()

    fun requester(entry: SlotEntry): FocusRequester =
        rows.getOrPut(Triple(entry.shared, entry.owner, entry.slot)) { FocusRequester() }
}

@Composable
fun rememberSaveRowFocus(): SaveRowFocus = remember { SaveRowFocus() }

/**
 * The save rows the Saves page and the slot panel show: [entries], then, under "Other saves", the other discs' [others].
 * Each row is [enabled] as its owner says, picks with [onPick], and, when [onDelete] is given, deletes a saved row.
 * Their focus is [focus]'s; each owner decides which row takes it.
 */
@Composable
internal fun SaveRows(
    entries: List<SlotEntry>?,
    others: List<SlotEntry>,
    now: ZonedDateTime,
    short: Boolean,
    focus: SaveRowFocus,
    enabled: (SlotEntry) -> Boolean,
    onPick: (SlotEntry) -> Unit,
    onDelete: ((SlotEntry) -> Unit)?,
) {
    @Composable
    fun row(entry: SlotEntry) =
        SlotRow(
            entry,
            now,
            short = short,
            enabled = enabled(entry),
            onClick = { onPick(entry) },
            modifier = Modifier.focusRequester(focus.requester(entry)),
            onDelete = onDelete?.takeIf { entry.savedAt != null }?.let { { it(entry) } },
        )

    entries?.forEach { row(it) }
    if (others.isNotEmpty()) {
        Eyebrow("Other saves", Modifier.padding(top = 12.dp, start = 8.dp))
        others.forEach { row(it) }
    }
}

/** What [lists] say when they have no row to show: that the game keeps no states of its own, or that none are saved. */
@Composable
internal fun SaveListEmptyLine(lists: SaveListState) {
    val sharedTab = lists.tab == SlotTab.Shared
    val entries = lists.shownEntries
    when {
        !sharedTab && !lists.hasKey -> SlotLine(NO_KEY_STATES)
        entries != null && lists.mode == SlotMode.Load && entries.none { it.savedAt != null } &&
            lists.shownOthers.isEmpty() ->
            SlotLine(if (sharedTab) "No shared saves yet." else "No saves yet for this game.")
    }
}
