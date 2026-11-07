// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.PackJob
import io.github.ilyalissoboi.goosestation.app.PackRow
import io.github.ilyalissoboi.goosestation.app.ShaderPacksModel
import io.github.ilyalissoboi.goosestation.app.packProgressText
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.NavRow
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.ProgressBar
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

const val SHADER_PACKS_TITLE = "Download shader packs"
const val SHADER_PACKS_NOTE = "Third-party content under its own licences."
const val SHADER_PACKS_BUSY = "Busy with another pack."

// How far an unknown length's bar moves for each byte: across it every 8 MB.
private const val UNKNOWN_SWEEP_BYTES = 8L * 1024 * 1024

/** A pack's summary: its state, then what it is, its licence and its size. */
fun PackRow.summary(): String =
    listOf(words, info.description, info.licence, size)
        .filter {
            it.isNotEmpty()
        }.joinToString(" · ")

/**
 * The shader packs, in a panel over the Post-processing page: the licence note, each pack (A shows what can be done with
 * it, under it), and while a job runs its step, a progress bar and Cancel. Removing a pack asks first, in the panel.
 * B steps back: from the question, from a pack's actions, then closes the panel ([onClose]); a job goes on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShaderPacksPanel(
    model: ShaderPacksModel,
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) { model.open() }
    val job = model.job
    val busy = job != null
    val rows = model.rows
    val actions = model.actions
    val removing = model.removing

    val rowRequesters = remember { mutableMapOf<String, FocusRequester>() }

    fun rowRequester(id: String) = rowRequesters.getOrPut(id) { FocusRequester() }
    val firstAction = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    val keep = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    // The pack whose actions were open last: back from them, the focus goes to its row.
    var lastActions by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(actions) { if (actions != null) lastActions = actions }

    LaunchedEffect(rows.isNotEmpty(), busy, actions, removing) {
        runCatching {
            when {
                removing != null -> keep.requestFocus()
                actions != null -> firstAction.requestFocus()
                job != null && !job.removing -> cancel.requestFocus()
                rows.isNotEmpty() && !busy ->
                    rowRequester(lastActions?.takeIf { id -> rows.any { it.info.id == id } } ?: rows.first().info.id)
                        .requestFocus()
                else -> close.requestFocus()
            }
        }
    }

    OverlayPanel(
        onBack = {
            when {
                model.removing != null -> model.cancelRemove()
                model.actions != null -> model.closeActions()
                else -> onClose()
            }
        },
    ) {
        if (removing != null) {
            Text(
                model.removeQuestion(removing),
                style = MaterialTheme.typography.bodyLarge,
                color = GooseStationTheme.colors.ink,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton("Remove", onClick = model::confirmRemove, kind = ButtonKind.Danger)
                ActionButton(
                    "Cancel",
                    onClick = model::cancelRemove,
                    modifier = Modifier.focusRequester(keep),
                    kind = ButtonKind.Ghost,
                )
            }
            return@OverlayPanel
        }

        Text(SHADER_PACKS_TITLE, style = MaterialTheme.typography.titleLarge, color = GooseStationTheme.colors.ink)
        Text(SHADER_PACKS_NOTE, style = MaterialTheme.typography.bodyMedium, color = GooseStationTheme.colors.inkMuted)
        if (job != null) JobProgress(job, Modifier.focusRequester(cancel), model::cancel)
        model.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = GooseStationTheme.colors.inkMuted)
        }
        if (rows.isEmpty()) {
            Text(
                "Reading the packs…",
                style = MaterialTheme.typography.bodyMedium,
                color = GooseStationTheme.colors.inkMuted,
            )
        }
        for (row in rows) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NavRow(
                    row.info.name,
                    if (busy) SHADER_PACKS_BUSY else row.summary(),
                    onClick = { model.openActions(row.info.id) },
                    modifier = Modifier.focusRequester(rowRequester(row.info.id)),
                    enabled = !busy,
                )
                if (actions == row.info.id) PackActions(model, row, firstAction)
            }
        }
        FlowRow {
            ActionButton("Close", onClick = onClose, modifier = Modifier.focusRequester(close), kind = ButtonKind.Ghost)
        }
    }
}

/** A running job: its step and how far it is, a bar, and Cancel for an install. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobProgress(
    job: PackJob,
    cancelModifier: Modifier,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val detail = packProgressText(job.value, job.range)
        Text(
            if (detail.isEmpty()) job.step else "${job.step} $detail",
            style = MaterialTheme.typography.bodyLarge,
            color = GooseStationTheme.colors.ink,
        )
        ProgressBar(
            fraction = if (job.range > 0) job.value.toFloat() / job.range else null,
            phase = (job.value % UNKNOWN_SWEEP_BYTES).toFloat() / UNKNOWN_SWEEP_BYTES,
        )
        if (!job.removing) {
            FlowRow { ActionButton("Cancel", onClick = onCancel, modifier = cancelModifier, kind = ButtonKind.Ghost) }
        }
    }
}

/** What can be done with [row]'s pack, under its row: Install or Update first, Remove, and Back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackActions(
    model: ShaderPacksModel,
    row: PackRow,
    first: FocusRequester,
) {
    row.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = GooseStationTheme.colors.inkMuted) }
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
