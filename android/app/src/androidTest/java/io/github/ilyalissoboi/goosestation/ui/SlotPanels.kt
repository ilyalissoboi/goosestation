// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import io.github.ilyalissoboi.goosestation.app.SaveListState
import io.github.ilyalissoboi.goosestation.app.SlotEntry
import io.github.ilyalissoboi.goosestation.app.SlotMode
import io.github.ilyalissoboi.goosestation.app.SlotPanel
import io.github.ilyalissoboi.goosestation.app.SlotQuestion
import io.github.ilyalissoboi.goosestation.app.SlotTab
import io.github.ilyalissoboi.goosestation.app.SlotWork

/** A slot panel as the UI tests draw it, by what it lists and asks. */
internal fun slotPanel(
    mode: SlotMode,
    entries: List<SlotEntry>? = null,
    focus: Int? = null,
    question: SlotQuestion? = null,
    work: SlotWork? = null,
    error: String? = null,
    others: List<SlotEntry> = emptyList(),
    tab: SlotTab = SlotTab.ThisGame,
    shared: List<SlotEntry>? = null,
    sharedFocus: Int? = null,
    hasKey: Boolean = true,
    deleting: SlotEntry? = null,
    focusRow: SlotEntry? = null,
    removing: Boolean = false,
) = SlotPanel(
    SaveListState(mode, tab, hasKey, entries, others, shared, focus, sharedFocus, focusRow, deleting, removing, error),
    question,
    work,
)

/** The panel with its lists changed. */
internal fun SlotPanel.lists(change: (SaveListState) -> SaveListState) = copy(lists = change(lists))
