// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/** The test tag of a [CyclerRow]'s open list of choices. */
const val CHOICE_LIST = "choiceList"

/**
 * A page's open choice list: the [open] row's key, or null, and where that row is. A page has one ([SettingsPage]
 * provides it), so opening one list closes any other.
 */
@Stable
class ChoiceListHost {
    var open: Any? by mutableStateOf(null)
        private set

    /** Where the open row is, list and all, so a tap outside it can close it. */
    internal var openCoordinates: LayoutCoordinates? = null

    /** The open row's, for the focus to go back to when a tap outside closes its list. */
    private var openRow: FocusRequester? = null

    /** Opens [key]'s list; [row] takes the focus back if a tap outside closes it. */
    fun open(
        key: Any,
        row: FocusRequester? = null,
    ) {
        open = key
        openCoordinates = null
        openRow = row
    }

    fun close() {
        open = null
        openCoordinates = null
        openRow = null
    }

    /**
     * A tap that ended at [position], in root coordinates: outside the open row, it closes the list, the focus back on
     * the row (a tap doesn't move the focus, and the focused choice goes). What was tapped still acts.
     */
    internal fun tappedAt(position: Offset) {
        val coordinates = openCoordinates?.takeIf { it.isAttached } ?: return
        if (coordinates.boundsInRoot().contains(position)) return
        openRow?.requestFocus()
        close()
    }
}

/** The page's [ChoiceListHost], or null outside a [SettingsPage]. */
val LocalChoiceListHost = staticCompositionLocalOf<ChoiceListHost?> { null }

/**
 * A [CyclerRow]'s choices, open under its label line: each one's label and note, the [selected] one current. The
 * focus starts on the current choice (else the first) and can't leave the list; up and down move it, left and right
 * do nothing. A picks ([onPick]). If the focused choice goes, the focus goes to the current one, else the first.
 */
@Composable
internal fun <T> ChoiceList(
    choices: List<Choice<T>>,
    selected: T,
    onPick: (Choice<T>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    val values = choices.map { it.value }
    val requesters = remember(values) { values.map { FocusRequester() } }
    // Which choice has the focus, if one does: a choice's value can be null.
    var focused by remember { mutableStateOf(false) }
    var focusedValue by remember { mutableStateOf<Any?>(null) }
    // Each choice is composed by its value, so a choice keeps its focus when another before it goes. If the focused one
    // goes, the focus goes to the current one, else the first. Focusing a choice scrolls it to the middle, as for any
    // row; a second request, to show the whole list, would fight it (two rects can't both be centred, and the page
    // never settles).
    LaunchedEffect(values) {
        if (focused && values.any { it == focusedValue }) return@LaunchedEffect
        requesters.getOrNull(values.indexOf(selected).coerceAtLeast(0))?.requestFocus()
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(CHOICE_LIST)
                // Its own node, so the row's merged text stays the row's.
                .semantics(mergeDescendants = true) {}
                // The D-pad can't move the focus out; closing the list (a request for the row) can.
                .focusProperties { onExit = { if (requestedFocusDirection in MOVES) cancelFocusChange() } }
                .focusGroup()
                // Both halves of a left or right press, so neither moves the focus or steps the row.
                .onKeyEvent { it.key == Key.DirectionLeft || it.key == Key.DirectionRight }
                .background(colors.sunken, GsShapes.medium)
                .border(1.dp, colors.lineStrong, GsShapes.medium)
                // Room for the focus ring inside the well.
                .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        choices.forEachIndexed { index, choice ->
            key(choice.value) {
                val current = choice.value == selected
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(requesters[index])
                            .onFocusChanged {
                                if (it.isFocused) {
                                    focused = true
                                    focusedValue = choice.value
                                }
                            }.background(if (current) colors.beakSoft else colors.sunken, GsShapes.medium)
                            .focusRing(
                                role = Role.RadioButton,
                                focusedFill = if (current) null else colors.raised,
                                onClick = { onPick(choice) },
                            ).semantics { this.selected = current }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(choice.label, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                        choice.note?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                        }
                    }
                    if (current) {
                        Icon(
                            GsIcons.check,
                            contentDescription = null,
                            tint = colors.beakInk,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The moves a D-pad or keyboard makes, which can't take the focus out of an open list. */
private val MOVES =
    setOf(
        FocusDirection.Up,
        FocusDirection.Down,
        FocusDirection.Left,
        FocusDirection.Right,
        FocusDirection.Next,
        FocusDirection.Previous,
    )
