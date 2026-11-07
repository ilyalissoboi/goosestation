// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.PackRow
import io.github.ilyalissoboi.goosestation.app.ShaderPacksModel
import io.github.ilyalissoboi.goosestation.app.packProgressText
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.NavRow
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

const val SHADER_PACKS_TITLE = "Download shader packs"
const val SHADER_PACKS_NOTE = "Third-party content under its own licences."
const val SHADER_PACKS_BUSY = "Busy with another pack."

/** A pack's summary: its state, then what it is, its licence and its size. */
fun PackRow.summary(): String =
    listOf(words, info.description, info.licence, size)
        .filter {
            it.isNotEmpty()
        }.joinToString(" · ")

/**
 * The shader packs page: the note, each pack (A opens what can be done with it), and while a job runs its step and
 * progress with Cancel. B goes back ([onBack]); a job goes on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShaderPacksPage(
    model: ShaderPacksModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { model.open() }
    val first = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    val job = model.job
    val busy = job != null
    val rows = model.rows
    // The first pack, once they're read and whenever a job ends; Cancel while an install runs.
    LaunchedEffect(rows.isNotEmpty(), busy, model.actions, model.removing) {
        if (model.actions != null || model.removing != null) return@LaunchedEffect
        when {
            job != null && !job.removing -> cancel.requestFocus()
            rows.isNotEmpty() && !busy -> first.requestFocus()
        }
    }

    SettingsPage(SHADER_PACKS_TITLE, onBack = onBack) {
        Notice(NoticeStatus.Info, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(SHADER_PACKS_NOTE, style = MaterialTheme.typography.bodyMedium, color = GooseStationTheme.colors.ink)
        }
        if (job != null) {
            val detail = packProgressText(job.value, job.range)
            Text(
                if (detail.isEmpty()) job.step else "${job.step} $detail",
                style = MaterialTheme.typography.bodyLarge,
                color = GooseStationTheme.colors.ink,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (!job.removing) {
                FlowRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ActionButton(
                        "Cancel",
                        onClick = model::cancel,
                        modifier = Modifier.focusRequester(cancel),
                        kind = ButtonKind.Ghost,
                    )
                }
            }
        }
        model.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = GooseStationTheme.colors.inkMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (rows.isEmpty()) {
            Text(
                "Reading the packs…",
                style = MaterialTheme.typography.bodyMedium,
                color = GooseStationTheme.colors.inkMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        rows.forEachIndexed { index, row ->
            NavRow(
                row.info.name,
                if (busy) SHADER_PACKS_BUSY else row.summary(),
                onClick = { model.openActions(row.info.id) },
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                enabled = !busy,
            )
        }
    }
    model.actions?.let { id -> rows.firstOrNull { it.info.id == id }?.let { PackActionsPanel(model, it) } }
    model.removing?.let { id -> RemovePackPanel(model, id) }
}

/** What can be done with [row]'s pack: Install or Update first, Remove, and Back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackActionsPanel(
    model: ShaderPacksModel,
    row: PackRow,
) {
    OverlayPanel(onBack = model::closeActions) {
        val first = remember { FocusRequester() }
        LaunchedEffect(row.info.id) { first.requestFocus() }
        Text(row.info.name, style = MaterialTheme.typography.titleMedium, color = GooseStationTheme.colors.ink)
        Text(row.summary(), style = MaterialTheme.typography.bodyMedium, color = GooseStationTheme.colors.inkMuted)
        row.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = GooseStationTheme.colors.inkMuted,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var focused = false

            fun firstOne(): Modifier =
                if (focused) {
                    Modifier
                } else {
                    focused = true
                    Modifier.focusRequester(first)
                }
            if (row.canInstall) {
                ActionButton(
                    "Install",
                    onClick = { model.install(row.info.id) },
                    modifier = firstOne(),
                    kind = ButtonKind.Primary,
                )
            }
            if (row.canUpdate) {
                ActionButton(
                    "Update",
                    onClick = { model.install(row.info.id) },
                    modifier = firstOne(),
                    kind = ButtonKind.Primary,
                )
            }
            if (row.canRemove) {
                ActionButton(
                    "Remove",
                    onClick = { model.askRemove(row.info.id) },
                    modifier = firstOne(),
                    kind = ButtonKind.Danger,
                )
            }
            ActionButton("Back", onClick = model::closeActions, modifier = firstOne(), kind = ButtonKind.Ghost)
        }
    }
}

/** Removing a pack asks first: Remove, then Cancel, which is focused. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemovePackPanel(
    model: ShaderPacksModel,
    id: String,
) {
    OverlayPanel(onBack = model::cancelRemove) {
        val cancel = remember { FocusRequester() }
        LaunchedEffect(id) { cancel.requestFocus() }
        Text(model.removeQuestion(id), style = MaterialTheme.typography.bodyLarge, color = GooseStationTheme.colors.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Remove", onClick = model::confirmRemove, kind = ButtonKind.Danger)
            ActionButton(
                "Cancel",
                onClick = model::cancelRemove,
                modifier = Modifier.focusRequester(cancel),
                kind = ButtonKind.Ghost,
            )
        }
    }
}
