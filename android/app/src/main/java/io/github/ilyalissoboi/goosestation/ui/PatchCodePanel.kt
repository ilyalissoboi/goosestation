// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.CodePanel
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.PatchCodeRow
import io.github.ilyalissoboi.goosestation.ui.components.Eyebrow
import io.github.ilyalissoboi.goosestation.ui.components.KeyHint
import io.github.ilyalissoboi.goosestation.ui.components.KeyHints
import io.github.ilyalissoboi.goosestation.ui.components.LabelValue
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.SidePanel
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

/**
 * The Patch codes panel of the in-game menu, beside it as the slot panel is: the game's codes in their groups, each
 * under its heading. A code is a [SwitchRow], with its note under it; a code applied by hand is an Apply row instead.
 * Switching a code changes it at once, and the menu stays open. With no codes, the panel says which file to add. As it
 * opens, the first code takes the focus, which can't leave the panel: B and Back close it ([GameMenu] handles them).
 */
@Composable
fun PatchCodePanel(
    panel: CodePanel,
    actions: GameMenuActions,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    val title = remember { FocusRequester() }
    var titleFocused by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val groups = panel.groups
    val empty = groups != null && groups.isEmpty()

    LaunchedEffect(groups == null) {
        if (groups.isNullOrEmpty()) title.requestFocus() else first.requestFocus()
    }

    SidePanel(modifier) {
        // Focusable while there's no code to focus, so the focus stays in the panel and B reaches it. It stays
        // focusable while it has the focus, as the codes arrive until the first takes it: were it to stop first,
        // Android would put the focus on the window's first focusable, the menu's top, scrolling the menu (as
        // Achievements).
        Text(
            "Patch codes",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
            modifier =
                Modifier
                    .focusRequester(title)
                    .onFocusChanged { titleFocused = it.isFocused }
                    .focusable(enabled = groups.isNullOrEmpty() || titleFocused),
        )
        panel.error?.let { error ->
            Notice(NoticeStatus.Danger) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
            }
        }
        panel.applied?.let { applied ->
            Notice(NoticeStatus.Success) {
                Text(applied, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
            }
        }
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (empty) {
                Text("No patch codes for this game.", style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                Text(
                    buildAnnotatedString {
                        append("Add ")
                        withStyle(
                            SpanStyle(fontFamily = GooseStationTheme.type.mono.fontFamily),
                        ) { append(panel.fileName) }
                        append(" to the cheats folder in your data folder.")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.inkMuted,
                )
            }
            var firstRow = true
            groups.orEmpty().forEach { group ->
                group.title?.let { Eyebrow(it, Modifier.padding(top = 8.dp, start = 16.dp)) }
                group.codes.forEach { code ->
                    val focus = if (firstRow) Modifier.focusRequester(first) else Modifier
                    firstRow = false
                    if (code.manual) {
                        ApplyRow(code, onApply = { actions.applyCode(code.index) }, modifier = focus)
                    } else {
                        SwitchRow(
                            code.name,
                            code.on,
                            onToggle = { actions.setCode(code.index, it) },
                            modifier = focus,
                            note = code.note,
                            maxLines = CODE_LINES,
                        )
                    }
                }
            }
        }
        KeyHints(listOf(KeyHint(PadGlyphs.confirm, "Select"), KeyHint(PadGlyphs.back, "Back")))
    }
}

/**
 * A code's name and its note each show this many lines at most, then an ellipsis: a few of the database's run to
 * hundreds of characters, and a row taller than the panel couldn't be read whole on a small screen.
 */
private const val CODE_LINES = 2

/** A code applied by hand: its name, Apply at the end, and its note under it. A, Enter or a tap apply it. */
@Composable
private fun ApplyRow(
    code: PatchCodeRow,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(role = Role.Button, onClick = onApply)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LabelValue(
            label = {
                Text(
                    code.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    maxLines = CODE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            value = { Text("Apply", style = MaterialTheme.typography.bodyLarge, color = colors.inkMuted) },
        )
        code.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
                maxLines = CODE_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
