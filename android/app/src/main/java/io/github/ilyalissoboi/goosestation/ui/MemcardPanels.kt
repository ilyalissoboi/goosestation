// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.CardState
import io.github.ilyalissoboi.goosestation.app.EditorQuestion
import io.github.ilyalissoboi.goosestation.app.MemcardEditorActions
import io.github.ilyalissoboi.goosestation.app.MemcardEditorUi
import io.github.ilyalissoboi.goosestation.app.MemcardEditorWords
import io.github.ilyalissoboi.goosestation.app.SavePanel
import io.github.ilyalissoboi.goosestation.core.MemcardSave
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.TextFieldRow
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

/**
 * What shows over the memory card editor, if anything: its question, or the open save's panel (its actions, or its
 * rename form). B and Back go to [MemcardEditorActions.back], which drops the question, then closes the panel.
 */
@Composable
internal fun MemcardOverlay(
    ui: MemcardEditorUi,
    actions: MemcardEditorActions,
    onLeave: () -> Unit,
) {
    val question = ui.question
    val panel = ui.panel
    val save =
        panel?.let { open ->
            ui.panes[open.pane]
                ?.contents
                ?.saves
                ?.firstOrNull { it.firstBlock == open.firstBlock }
        }
    if (question == null && save == null) return
    OverlayPanel(onBack = { actions.back(onLeave) }) {
        when {
            question != null -> QuestionContent(question, actions)
            panel != null && save != null && panel.renaming -> RenameForm(save, panel, ui.renameError, actions)
            panel != null && save != null -> {
                val other = ui.panes[1 - panel.pane]?.takeIf { it.state != CardState.Unreadable }
                SaveActions(save, other?.file?.name, panel.pane, actions)
            }
        }
    }
}

/** The question: its text, then its destructive answer, any others, and the safe one, which has the focus. */
@Composable
private fun QuestionContent(
    question: EditorQuestion,
    actions: MemcardEditorActions,
) {
    val colors = GooseStationTheme.colors
    val safe = remember { FocusRequester() }
    LaunchedEffect(question) { safe.requestFocus() }
    Text(question.text, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(question.confirm, onClick = { actions.answer(true) }, kind = ButtonKind.Danger)
        question.choices.forEachIndexed { index, choice ->
            ActionButton(
                choice.label,
                onClick = { actions.choose(index) },
                kind = if (choice.danger) ButtonKind.Danger else ButtonKind.Secondary,
            )
        }
        ActionButton(
            question.safe,
            onClick = { actions.answer(false) },
            kind = ButtonKind.Ghost,
            modifier = Modifier.focusRequester(safe),
        )
    }
}

/** The save's title and file name, then what can be done with it: Copy first, and focused, for a live save. */
@Composable
private fun SaveActions(
    save: MemcardSave,
    otherCard: String?,
    pane: Int,
    actions: MemcardEditorActions,
) {
    val colors = GooseStationTheme.colors
    val first = remember { FocusRequester() }
    LaunchedEffect(save.firstBlock, save.deleted) { first.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (save.deleted) MemcardEditorWords.deleted(save.title) else save.title,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(save.fileName, style = GooseStationTheme.type.mono, color = colors.inkMuted)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val wide = Modifier.fillMaxWidth()
        if (save.deleted) {
            ActionButton("Undelete", onClick = { actions.undelete(pane, save) }, modifier = wide.focusRequester(first))
            ActionButton(
                "Delete permanently",
                onClick = { actions.deletePermanently(pane, save) },
                kind = ButtonKind.Danger,
                modifier = wide,
            )
        } else {
            val copy = otherCard?.let { MemcardEditorWords.copyTo(it) }
            if (copy != null) {
                ActionButton(copy, onClick = { actions.copy(pane, save) }, modifier = wide.focusRequester(first))
            }
            ActionButton(
                "Rename…",
                onClick = actions::startRename,
                modifier = if (copy == null) wide.focusRequester(first) else wide,
            )
            ActionButton("Export…", onClick = { actions.export(pane, save) }, modifier = wide)
            ActionButton("Delete", onClick = { actions.delete(pane, save) }, kind = ButtonKind.Danger, modifier = wide)
        }
    }
}

/**
 * The rename form: the title and the file name, as typed so far ([SavePanel.title], [SavePanel.fileName]) or as they
 * are, the rule they break ([error]) if any, then Rename and Cancel. The title's field has the focus.
 */
@Composable
private fun RenameForm(
    save: MemcardSave,
    panel: SavePanel,
    error: String?,
    actions: MemcardEditorActions,
) {
    val colors = GooseStationTheme.colors
    val title = remember(save.firstBlock) { TextFieldState(panel.title ?: save.title) }
    val fileName = remember(save.firstBlock) { TextFieldState(panel.fileName ?: save.fileName) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    val rename = { actions.rename(title.text.toString(), fileName.text.toString()) }
    Text("Rename", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
    TextFieldRow("Title", title, modifier = Modifier.focusRequester(first))
    TextFieldRow("File name", fileName, imeAction = ImeAction.Done, onDone = rename)
    error?.let {
        Notice(NoticeStatus.Danger) { Text(it, style = MaterialTheme.typography.titleMedium, color = colors.ink) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Rename", onClick = rename)
        ActionButton("Cancel", onClick = actions::closePanel, kind = ButtonKind.Ghost)
    }
}
