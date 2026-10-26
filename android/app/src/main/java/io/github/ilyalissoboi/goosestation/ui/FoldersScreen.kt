// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
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
import io.github.ilyalissoboi.goosestation.mirror.DataFolderChoice
import io.github.ilyalissoboi.goosestation.storage.DataFolderInspection
import io.github.ilyalissoboi.goosestation.storage.SavedFolders
import io.github.ilyalissoboi.goosestation.storage.folderLabel
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.PICKER_HINT
import kotlinx.coroutines.launch

/** A folder picked to become the data folder, and what it holds, or why it can't be the data folder. */
data class DataFolderCandidate(
    val uri: String,
    val inspection: DataFolderInspection,
)

/**
 * The folder settings: the BIOS folder and the images last imported ([biosImages]), the games folders, and the data
 * folder with its [dataStatus]. The BIOS **Change** is focused on opening; B or Back go back ([onBack]).
 *
 * [onChangeBios] picks and imports another BIOS folder, [onRescanBios] imports the same one again, [onAddGames] picks
 * a games folder to add, and [onSetGames] takes the games folders as they're to be. [onChangeData] picks a new data
 * folder, which comes back as the [candidate]: the screen then shows what it holds and asks, in the screen, what to
 * do with it ([onChooseData]), with B or **Cancel** ([onCancelData]) going back to the folders. A refused candidate
 * gets no choice, only why, and **Pick another**. [dataReachable] is
 * false when the data folder's latest changes may not be in it: the choice then warns that they're lost. While
 * [progress] is set, a change is under way and nothing else is offered. [message] is the app's latest error
 * ([isError]) or what it's doing.
 */
@Composable
fun FoldersScreen(
    folders: SavedFolders,
    biosImages: List<String>,
    dataStatus: String,
    dataReachable: Boolean,
    candidate: DataFolderCandidate?,
    progress: String?,
    message: String?,
    isError: Boolean,
    onChangeBios: () -> Unit,
    onRescanBios: () -> Unit,
    onAddGames: () -> Unit,
    onSetGames: (List<String>) -> Unit,
    onChangeData: () -> Unit,
    onChooseData: (DataFolderChoice) -> Unit,
    onCancelData: () -> Unit,
    onBack: () -> Unit,
) {
    val biosChange = remember { FocusRequester() }
    val addGames = remember { FocusRequester() }
    val dataChange = remember { FocusRequester() }
    val firstChoice = remember { FocusRequester() }
    // Once a candidate has come and gone, the data folder's Change takes the focus back, not the BIOS one.
    var choseData by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val choosing = candidate != null

    // The element that has the focus when nothing else should: on opening, and once the focused one goes.
    fun primary() =
        when {
            choosing -> firstChoice
            choseData -> dataChange
            else -> biosChange
        }

    fun back() {
        when {
            progress != null -> {}
            choosing -> onCancelData()
            else -> onBack()
        }
    }

    LaunchedEffect(choosing, progress == null) {
        if (progress != null) return@LaunchedEffect
        if (choosing) {
            choseData = true
            // From the top, so the choice is read from its heading on.
            scroll.scrollTo(0)
        }
        primary().requestFocus()
    }
    // Always on: Back mustn't close the app, even mid-change.
    BackHandler(onBack = ::back)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                // Something always has focus, or B would reach nothing: focus lost to a removed button goes back to
                // the primary one, once the frame has it.
                .onFocusChanged { state ->
                    if (!state.hasFocus && progress == null) {
                        scope.launch {
                            withFrameNanos {}
                            primary().requestFocus()
                        }
                    }
                }.onPreviewKeyEvent { event ->
                    if (event.key != Key.ButtonB) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) back()
                    true
                }.verticalScroll(scroll)
                .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Folders", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        if (message != null) MessageBar(message, isError)

        when {
            progress != null -> Body(progress)
            candidate != null ->
                DataChoice(candidate, dataReachable, firstChoice, onChooseData, onCancelData, onChangeData)
            else -> {
                Heading("BIOS folder")
                Body(folders.bios?.let(::folderLabel) ?: "None")
                biosImages.forEach { Body(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Change", onClick = onChangeBios, modifier = Modifier.focusRequester(biosChange))
                    ActionButton("Rescan", onClick = onRescanBios, enabled = folders.bios != null)
                }
                Hint()

                Heading("Games folders")
                folders.games.forEach { uri ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Before the name, so it lines up with the buttons above and below for the D-pad.
                        ActionButton(
                            "Remove",
                            onClick = {
                                // Before the button goes, so focus moves straight to Add folder.
                                addGames.requestFocus()
                                onSetGames(folders.games - uri)
                            },
                        )
                        Body(folderLabel(uri))
                    }
                }
                ActionButton("Add folder", onClick = onAddGames, modifier = Modifier.focusRequester(addGames))
                Hint()

                Heading("Data folder")
                Body(folders.data?.let(::folderLabel) ?: "None")
                Body(dataStatus)
                ActionButton("Change", onClick = onChangeData, modifier = Modifier.focusRequester(dataChange))
                Hint()
            }
        }
    }
}

/** What the picked folder holds, and the two choices, the first focused through [first]; then Cancel. */
@Composable
private fun DataChoice(
    candidate: DataFolderCandidate,
    dataReachable: Boolean,
    first: FocusRequester,
    onChoose: (DataFolderChoice) -> Unit,
    onCancel: () -> Unit,
    onPickAnother: () -> Unit,
) {
    Heading("Change the data folder")
    Body("New data folder: ${folderLabel(candidate.uri)}")
    val contents =
        when (val inspection = candidate.inspection) {
            is DataFolderInspection.Usable -> inspection.contents
            is DataFolderInspection.Refused -> {
                Body(inspection.reason)
                ActionButton("Pick another", onClick = onPickAnother, modifier = Modifier.focusRequester(first))
                Hint()
                ActionButton("Cancel", onClick = onCancel)
                return
            }
        }
    Body(contents.summary())
    Body("GooseStation restarts once the change is made.")
    ActionButton(
        "Copy this device's data into it",
        onClick = { onChoose(DataFolderChoice.CopyThisDevicesData) },
        modifier = Modifier.focusRequester(first),
    )
    Body("Your settings, memory cards and save states go into it, over its files of the same name.")
    ActionButton("Use the data already there", onClick = { onChoose(DataFolderChoice.UseFolderData) })
    Body(
        if (dataReachable) {
            "This device's data is replaced with the folder's. Your old data folder keeps its copy."
        } else {
            "This device's data is replaced with the folder's. Some changes aren't in your old data folder, which " +
                "can't be reached or didn't take them, and are lost."
        },
    )
    ActionButton("Cancel", onClick = onCancel)
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Hint() {
    Text(PICKER_HINT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
