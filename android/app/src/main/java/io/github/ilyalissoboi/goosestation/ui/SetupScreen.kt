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
import io.github.ilyalissoboi.goosestation.storage.BiosImportResult
import io.github.ilyalissoboi.goosestation.storage.DataFolderCheck
import io.github.ilyalissoboi.goosestation.storage.DataFolderInspection
import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.storage.SavedFolders
import io.github.ilyalissoboi.goosestation.storage.folderLabel
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import kotlinx.coroutines.launch

private enum class SetupStep { Welcome, Bios, Games, Data }

/**
 * First-run setup: the BIOS, games and data folders, one step at a time, each step's main button focused when it
 * opens, and B or Back going back a step. [pickFolder] opens the system picker (null if nothing was picked),
 * [importBios] copies a BIOS folder's images, and [inspectDataFolder] says what a data folder holds, given the games
 * folders, or why it can't be the data folder ([DataFolderCheck]); Finish waits for a usable one. A games folder that
 * overlaps the data folder picked is refused. [releaseFolder] lets go of a folder picked, then replaced or removed.
 * [onFinish] gets the folders, marked set up.
 */
@Composable
fun SetupScreen(
    pickFolder: suspend (FolderKind) -> String?,
    importBios: suspend (String) -> BiosImportResult,
    inspectDataFolder: suspend (String, List<String>) -> DataFolderInspection,
    releaseFolder: (String) -> Unit,
    onFinish: (SavedFolders) -> Unit,
) {
    var step by remember { mutableStateOf(SetupStep.Welcome) }
    var bios by remember { mutableStateOf<String?>(null) }
    var biosResult by remember { mutableStateOf<BiosImportResult?>(null) }
    var games by remember { mutableStateOf(emptyList<String>()) }
    var data by remember { mutableStateOf<String?>(null) }
    var dataInspection by remember { mutableStateOf<DataFolderInspection?>(null) }
    // Why the games folder last picked wasn't added, until the next pick.
    var gamesRefusal by remember { mutableStateOf<String?>(null) }
    // A pick, an import or a listing is under way: the buttons wait for it.
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val primary = remember { FocusRequester() }

    fun folders(setupComplete: Boolean = false) = SavedFolders(bios, games, data, setupComplete)

    // A folder picked again, or removed, keeps its grant if another step holds it too.
    fun releaseIfUnused(uri: String?) {
        if (uri != null && !folders().uses(uri)) releaseFolder(uri)
    }

    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    fun back() {
        step = SetupStep.entries[step.ordinal - 1]
    }

    LaunchedEffect(step) { primary.requestFocus() }
    BackHandler(enabled = step != SetupStep.Welcome, onBack = ::back)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                // Something always has focus, or keys (B among them) would reach nothing here: focus lost to a
                // removed or disabled button goes back to the step's main one, once the frame has it.
                .onFocusChanged { state ->
                    if (!state.hasFocus) {
                        scope.launch {
                            withFrameNanos {}
                            primary.requestFocus()
                        }
                    }
                }
                // B, and a Back key, which otherwise would close the app from any step.
                .onPreviewKeyEvent { event ->
                    if (step == SetupStep.Welcome || (event.key != Key.Back && event.key != Key.ButtonB)) {
                        return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyUp) back()
                    true
                }.verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GooseStation", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        if (step != SetupStep.Welcome) {
            Text("Step ${step.ordinal} of 3", style = MaterialTheme.typography.labelLarge, color = dim())
        }

        when (step) {
            SetupStep.Welcome -> {
                Heading("Welcome")
                Body(
                    "GooseStation needs three folders, picked one at a time:\n" +
                        "• BIOS folder: your PS1 BIOS images. GooseStation keeps a copy of them for itself.\n" +
                        "• Games folders: one or more folders of games, played from where they are.\n" +
                        "• Data folder: everything GooseStation keeps for you, such as settings, memory cards and " +
                        "save states.",
                )
                Body("Android's folder picker may need touch.")
                ActionButton("Start", onClick = { step = SetupStep.Bios }, modifier = Modifier.focusRequester(primary))
            }

            SetupStep.Bios -> {
                Heading("BIOS folder")
                Body("Pick the folder that holds your PS1 BIOS images. Android's folder picker may need touch.")
                ActionButton(
                    "Pick folder",
                    onClick = {
                        work {
                            val picked = pickFolder(FolderKind.Bios) ?: return@work
                            val old = bios
                            bios = picked
                            if (old != picked) releaseIfUnused(old)
                            biosResult = null
                            biosResult = importBios(picked)
                        }
                    },
                    modifier = Modifier.focusRequester(primary),
                )
                bios?.let { Body(folderLabel(it)) }
                val result = biosResult
                when {
                    busy && bios != null -> Body("Looking for BIOS images…")
                    result == null -> {}
                    result.error != null -> Body("${result.error} Pick another.")
                    result.images.isEmpty() -> Body("No PS1 BIOS found in this folder. Pick another.")
                    else -> result.images.forEach { Body(it) }
                }
                Navigation(nextEnabled = !result?.images.isNullOrEmpty(), onNext = { step = SetupStep.Games }, ::back)
            }

            SetupStep.Games -> {
                Heading("Games folders")
                Body("Add each folder that holds your games. Android's folder picker may need touch.")
                ActionButton(
                    "Add folder",
                    onClick = {
                        work {
                            val picked = pickFolder(FolderKind.Games) ?: return@work
                            val current = data
                            gamesRefusal = null
                            when {
                                current != null && DataFolderCheck.overlaps(picked, current) -> {
                                    gamesRefusal = DataFolderCheck.GAMES_OVERLAP_DATA
                                    releaseIfUnused(picked)
                                }
                                picked !in games -> games = games + picked
                            }
                        }
                    },
                    modifier = Modifier.focusRequester(primary),
                )
                games.forEach { uri ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Before the name, so it lines up with the buttons above and below for the D-pad.
                        ActionButton(
                            "Remove",
                            onClick = {
                                // Before the button goes, so focus moves straight to Add folder.
                                primary.requestFocus()
                                games = games - uri
                                releaseIfUnused(uri)
                            },
                        )
                        Body(folderLabel(uri))
                    }
                }
                gamesRefusal?.let { Body(it) }
                Navigation(nextEnabled = games.isNotEmpty(), onNext = { step = SetupStep.Data }, ::back)
            }

            SetupStep.Data -> {
                Heading("Data folder")
                Body(
                    "Pick a folder for your settings, memory cards and save states. It can be empty, or hold data " +
                        "GooseStation saved before. Android's folder picker may need touch.",
                )
                ActionButton(
                    "Pick folder",
                    onClick = {
                        work {
                            val picked = pickFolder(FolderKind.Data) ?: return@work
                            val old = data
                            data = picked
                            if (old != picked) releaseIfUnused(old)
                            dataInspection = null
                            dataInspection = inspectDataFolder(picked, games)
                        }
                    },
                    modifier = Modifier.focusRequester(primary),
                )
                data?.let { Body(folderLabel(it)) }
                val inspection = dataInspection
                when {
                    data == null -> {}
                    busy -> Body("Looking at the folder…")
                    inspection is DataFolderInspection.Usable -> Body(inspection.contents.describe())
                    inspection is DataFolderInspection.Refused -> Body(inspection.reason)
                }
                Navigation(
                    nextEnabled = data != null && inspection is DataFolderInspection.Usable && !busy,
                    onNext = { onFinish(folders(setupComplete = true)) },
                    onBack = ::back,
                    nextLabel = "Finish",
                )
            }
        }
    }
}

@Composable
private fun dim() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = dim())
}

/** Next (or Finish) first, under the step's main button, then Back for touch. */
@Composable
private fun Navigation(
    nextEnabled: Boolean,
    onNext: () -> Unit,
    onBack: () -> Unit,
    nextLabel: String = "Next",
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(nextLabel, onClick = onNext, enabled = nextEnabled)
        ActionButton("Back", onClick = onBack)
    }
}
