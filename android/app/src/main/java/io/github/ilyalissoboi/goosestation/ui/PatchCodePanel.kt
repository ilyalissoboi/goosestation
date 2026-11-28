// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import io.github.ilyalissoboi.goosestation.app.CodeTab
import io.github.ilyalissoboi.goosestation.app.CodeValues
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.PatchCodeList
import io.github.ilyalissoboi.goosestation.app.PatchCodeRow
import io.github.ilyalissoboi.goosestation.app.PatchRow
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.Eyebrow
import io.github.ilyalissoboi.goosestation.ui.components.KeyHint
import io.github.ilyalissoboi.goosestation.ui.components.KeyHints
import io.github.ilyalissoboi.goosestation.ui.components.LabelValue
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.PanelTabs
import io.github.ilyalissoboi.goosestation.ui.components.Question
import io.github.ilyalissoboi.goosestation.ui.components.SidePanel
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.components.tabKeys
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

/**
 * The Patches & cheats panel of the in-game menu, beside it as the slot panel is, with two tabs (L1 and R1 switch
 * them): the game's patches (widescreen, frame-rate and display-mode codes), then its cheats, each in their groups
 * under their headings. A plain patch is a [SwitchRow]; a patch with choices, and a code with values, a [CyclerRow]; a
 * cheat applied by hand is an Apply row. Changing one changes it at once, and the menu stays open; a patch that can't
 * be undone while the game runs asks first: reset now, at the next start, or cancel. Each row's note says what waits
 * for the next start, and what the patch runs with. With no codes, a tab says so (and Cheats which file to add). As it
 * opens, the first row takes the focus, which can't leave the panel: B and Back close it ([GameMenu] handles them).
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
    val onPatches = panel.tab == CodeTab.Patches
    val loaded = if (onPatches) panel.patches else panel.groups
    val empty = loaded != null && loaded.isEmpty()
    val question = panel.question

    LaunchedEffect(panel.tab, loaded == null, question == null) {
        if (question != null) return@LaunchedEffect
        if (loaded.isNullOrEmpty()) title.requestFocus() else first.requestFocus()
    }

    val switchTab = { actions.setCodeTab(if (onPatches) CodeTab.Cheats else CodeTab.Patches) }
    SidePanel(modifier.tabKeys { if (question == null) switchTab() }) {
        // Focusable while there's no row to focus, so the focus stays in the panel and B reaches it. It stays
        // focusable while it has the focus, as the rows arrive until the first takes it: were it to stop first,
        // Android would put the focus on the window's first focusable, the menu's top, scrolling the menu (as
        // Achievements).
        Box(
            Modifier
                .focusRequester(title)
                .onFocusChanged { titleFocused = it.isFocused }
                .focusable(enabled = loaded.isNullOrEmpty() || titleFocused),
        ) {
            PanelTabs(
                listOf("Patches", "Cheats"),
                current = if (onPatches) 0 else 1,
                onSelect = { actions.setCodeTab(if (it == 0) CodeTab.Patches else CodeTab.Cheats) },
            )
        }
        panel.error?.let { error ->
            Notice(NoticeStatus.Danger, error)
        }
        panel.applied?.let { applied ->
            Notice(NoticeStatus.Success, applied)
        }
        if (question != null) {
            Question(
                "“${question.title}” can't be undone while the game runs. Reset the game now, or change it at the " +
                    "next start? Until then it stays as it is.",
                confirm = "Reset now",
                onConfirm = { actions.answerPatchQuestion(true) },
                onCancel = { actions.answerPatchQuestion(null) },
                key = question,
                extra = { ActionButton("At next start", onClick = { actions.answerPatchQuestion(false) }) },
            )
        } else {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onPatches) {
                    if (empty) {
                        Text(
                            "No patches for this game.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.ink,
                        )
                        Text(
                            "Patches are widescreen, frame-rate and display-mode codes.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.inkMuted,
                        )
                    }
                    PatchRows(panel.patches.orEmpty(), actions, first)
                } else {
                    if (empty) {
                        Text("No cheats for this game.", style = MaterialTheme.typography.bodyLarge, color = colors.ink)
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
                    CheatRows(panel, actions, first)
                }
            }
        }
        KeyHints(
            listOf(
                KeyHint(PadGlyphs.confirm, "Select"),
                KeyHint("${PadGlyphs.previousTab} ${PadGlyphs.nextTab}", "Switch"),
                KeyHint(PadGlyphs.back, "Back"),
            ),
        )
    }
}

/** The game's patches in their groups: the first row takes [first]. */
@Composable
private fun PatchRows(
    patches: List<PatchRow>,
    actions: GameMenuActions,
    first: FocusRequester,
) {
    var group: String? = null
    patches.forEachIndexed { i, patch ->
        if (patch.group != null && patch.group != group) {
            Eyebrow(patch.group, Modifier.padding(top = 8.dp, start = 16.dp))
        }
        group = patch.group
        val focus = if (i == 0) Modifier.focusRequester(first) else Modifier
        val note = listOfNotNull(PatchCodeList.pending(patch), patch.note).joinToString(" · ").ifEmpty { null }
        if (patch.choices.size == 1) {
            SwitchRow(
                patch.title,
                patch.saved == 0,
                onToggle = { actions.choosePatch(patch.index, if (it) 0 else -1) },
                modifier = focus,
                note = note,
                maxLines = CODE_LINES,
                enabled = patch.canEnable,
            )
        } else {
            CyclerRow(
                patch.title,
                listOf(Choice(-1, "Off")) + patch.choices.mapIndexed { c, name -> Choice(c, name) },
                selected = patch.saved,
                onSelect = { actions.choosePatch(patch.index, it) },
                modifier = focus,
                note = note,
                enabled = patch.canEnable,
            )
        }
        patch.values?.takeIf { patch.canEnable }?.let { values ->
            ValueRow(values) { actions.setCodeValue(true, patch.index, maxOf(patch.saved, 0), it) }
        }
    }
}

/** The game's cheats in their groups, as before: the first row takes [first]. */
@Composable
private fun CheatRows(
    panel: CodePanel,
    actions: GameMenuActions,
    first: FocusRequester,
) {
    var firstRow = true
    panel.groups.orEmpty().forEach { group ->
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
            code.values?.let { values -> ValueRow(values) { actions.setCodeValue(false, code.index, 0, it) } }
        }
    }
}

/**
 * A code's value: its options, or the numbers of its range (up to [MAX_RANGE_CHOICES] of them; a wider range shows its
 * value, to set in the file).
 */
@Composable
private fun ValueRow(
    values: CodeValues,
    onSelect: (Int) -> Unit,
) {
    val range = values.range
    when {
        values.options.isNotEmpty() ->
            CyclerRow("Value", values.options.map { Choice(it.second, it.first) }, values.value, onSelect)
        range != null && range.last - range.first < MAX_RANGE_CHOICES ->
            CyclerRow("Value", range.map { Choice(it, it.toString()) }, values.value, onSelect)
        else ->
            LabelValue(
                label = { Text("Value", style = MaterialTheme.typography.titleMedium) },
                value = { Text(values.value.toString(), style = MaterialTheme.typography.bodyLarge) },
            )
    }
}

/** A range of values shows as a list of its numbers up to this many. */
private const val MAX_RANGE_CHOICES = 1000

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
