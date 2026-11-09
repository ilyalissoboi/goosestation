// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.CoverJob
import io.github.ilyalissoboi.goosestation.app.CoverSource
import io.github.ilyalissoboi.goosestation.app.CoversModel
import io.github.ilyalissoboi.goosestation.app.coverProgressText
import io.github.ilyalissoboi.goosestation.settings.coverSourceProblem
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.ProgressBar
import io.github.ilyalissoboi.goosestation.ui.components.SettingsLoadingPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.components.TextFieldRow
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

private const val COVERS_TITLE = "Covers"

/** Under the sources: what the variables stand for, and that the sources are the player's. */
const val COVERS_NOTE =
    "\${serial}, \${title} and \${filetitle} stand for each game's serial, title and file name. GooseStation hosts " +
        "no covers: the sources are yours to choose."

/**
 * The Covers page: the [model]'s sources, each a text row with Remove beside it, then Add a source and Restore the
 * preset source; Save covers by serial; and Download covers, its progress and Cancel while it runs, and how the last
 * one went. Restore asks first in an [OverlayPanel]. B or Back go back ([onBack]).
 *
 * The focus: on opening, the first source (or Add a source with none); after Add a source, the new one; after a
 * Remove, the source that took its place, else the one before, else Add a source; Cancel as a download starts, and
 * Download covers once it ends.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoversScreen(
    model: CoversModel,
    onBack: () -> Unit,
) {
    val sources = model.sources
    if (sources == null) {
        SettingsLoadingPage(COVERS_TITLE, onBack = onBack)
        return
    }

    val fieldRequesters = remember { mutableMapOf<Int, FocusRequester>() }

    fun fieldRequester(id: Int) = fieldRequesters.getOrPut(id) { FocusRequester() }
    val add = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val download = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    // Where the focus goes next, as the page changes under it; null leaves it where it is.
    var focusTarget by remember {
        mutableStateOf<FocusRequester?>(sources.firstOrNull()?.let { fieldRequester(it.id) } ?: add)
    }
    // A download starting or ending, and the question closing, as last seen: the focus follows a change of either.
    val running = model.job != null
    var wasRunning by remember { mutableStateOf(running) }
    LaunchedEffect(running) {
        if (running == wasRunning) return@LaunchedEffect
        wasRunning = running
        focusTarget = if (running) cancel else download
    }
    var wasRestoring by remember { mutableStateOf(model.restoring) }
    LaunchedEffect(model.restoring) {
        if (model.restoring == wasRestoring) return@LaunchedEffect
        wasRestoring = model.restoring
        if (!model.restoring && model.canRestore) focusTarget = restore
    }
    LaunchedEffect(focusTarget, sources.map { it.id }) {
        val target = focusTarget ?: return@LaunchedEffect
        runCatching { target.requestFocus() }
        focusTarget = null
    }

    // After a restore: the model's sources, read now, as the page hasn't recomposed with them yet.
    fun firstField() = model.sources?.firstOrNull()?.let { fieldRequester(it.id) }

    fun remove(source: CoverSource) {
        val index = sources.indexOf(source)
        val next = sources.getOrNull(index + 1) ?: sources.getOrNull(index - 1)
        model.remove(source.id)
        focusTarget = next?.let { fieldRequester(it.id) } ?: add
    }

    Box(Modifier.fillMaxSize()) {
        SettingsPage(COVERS_TITLE, onBack = onBack) {
            SettingsSection("Sources")
            if (sources.isEmpty()) {
                Body("No sources. Add one, or restore the preset source.")
            }
            sources.forEachIndexed { index, source ->
                key(source.id) {
                    SourceRow(
                        number = index + 1,
                        source = source,
                        requester = fieldRequester(source.id),
                        above = sources.getOrNull(index - 1)?.let { fieldRequester(it.id) },
                        below = sources.getOrNull(index + 1)?.let { fieldRequester(it.id) } ?: add,
                        onEdit = { model.edit(source.id, it) },
                        onRemove = { remove(source) },
                    )
                }
            }
            Buttons {
                // Right reaches Restore beside it, or under it once the buttons wrap.
                ActionButton(
                    "Add a source",
                    onClick = { focusTarget = fieldRequester(model.add()) },
                    modifier = Modifier.focusRequester(add).focusProperties { if (model.canRestore) right = restore },
                )
                if (model.canRestore) {
                    ActionButton(
                        "Restore the preset source",
                        onClick = {
                            model.askRestore()
                            if (!model.restoring) focusTarget = firstField()
                        },
                        modifier = Modifier.focusRequester(restore),
                        kind = ButtonKind.Ghost,
                    )
                }
            }
            Body(COVERS_NOTE)

            SettingsSection("Downloading")
            SwitchRow(
                "Save covers by serial",
                model.useSerial,
                onToggle = model::changeUseSerial,
                note = "Off saves them by title. A game without a serial is saved by title.",
            )
            val job = model.job
            if (job != null) {
                Progress(job, Modifier.focusRequester(cancel), model::cancel)
            } else {
                val blocked = model.blocked
                Buttons {
                    ActionButton(
                        "Download covers",
                        onClick = model::download,
                        modifier = Modifier.focusRequester(download),
                        enabled = blocked == null,
                        kind = ButtonKind.Primary,
                    )
                }
                blocked?.let { Body(it) }
            }
            model.message?.let { Body(it) }
        }
        if (model.restoring) {
            CoversRestoreQuestion(
                onReplace = {
                    model.confirmRestore()
                    focusTarget = firstField()
                },
                onKeep = model::cancelRestore,
            )
        }
    }
}

/** A source: its field, labelled by its [number], its note when it has no variable, and Remove beside it. */
@Composable
private fun SourceRow(
    number: Int,
    source: CoverSource,
    requester: FocusRequester,
    above: FocusRequester?,
    below: FocusRequester,
    onEdit: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val state: TextFieldState = rememberTextFieldState(source.text)
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { onEdit(it) } }
    TextFieldRow(
        "Source $number",
        state,
        // Up and down go from source to source, Remove's row included, rather than to a Remove nearer below.
        modifier =
            Modifier.focusRequester(requester).focusProperties {
                above?.let { up = it }
                down = below
            },
        imeAction = ImeAction.Done,
        note = coverSourceProblem(source.text),
        trailing = { ActionButton("Remove", onClick = onRemove, kind = ButtonKind.Ghost) },
    )
}

/** A download under way: its step, a bar, and Cancel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Progress(
    job: CoverJob,
    cancelModifier: Modifier,
    onCancel: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(coverProgressText(job), style = MaterialTheme.typography.bodyLarge, color = GooseStationTheme.colors.ink)
        ProgressBar(fraction = if (job.total > 0) job.position.toFloat() / job.total else 0f)
        FlowRow { ActionButton("Cancel", onClick = onCancel, modifier = cancelModifier, kind = ButtonKind.Ghost) }
    }
}

/** Before Restore the preset source replaces the player's sources: Replace, then Keep mine, focused. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoversRestoreQuestion(
    onReplace: () -> Unit,
    onKeep: () -> Unit,
) {
    OverlayPanel(onBack = onKeep) {
        val keep = remember { FocusRequester() }
        LaunchedEffect(Unit) { keep.requestFocus() }
        Text(
            "Replace your sources with the preset source?",
            style = MaterialTheme.typography.bodyLarge,
            color = GooseStationTheme.colors.ink,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton("Replace", onClick = onReplace, kind = ButtonKind.Danger)
            ActionButton(
                "Keep mine",
                onClick = onKeep,
                modifier = Modifier.focusRequester(keep),
                kind = ButtonKind.Ghost,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = GooseStationTheme.colors.inkMuted,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
