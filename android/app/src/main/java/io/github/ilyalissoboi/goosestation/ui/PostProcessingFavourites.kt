// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.FavouriteQuestion
import io.github.ilyalissoboi.goosestation.app.Naming
import io.github.ilyalissoboi.goosestation.app.PostProcessingModel
import io.github.ilyalissoboi.goosestation.core.FavouriteInfo
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.ButtonRow
import io.github.ilyalissoboi.goosestation.ui.components.NavRow
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.Question
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection
import io.github.ilyalissoboi.goosestation.ui.components.TextFieldRow
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import kotlinx.coroutines.launch

const val FAVOURITES_SECTION = "Favourites"
const val SAVE_FAVOURITE = "Save as favourite"
const val NO_FAVOURITES = "No favourites yet: save the current chain as one."

/**
 * The favourites section of the chains page, under the Display chain: each favourite numbered in order, with its
 * shaders (or those not installed here), then "Save as favourite". [requester] gives each row's focus requester, the
 * favourite's index, one past the last for "Save as favourite".
 */
@Composable
fun FavouriteRows(
    model: PostProcessingModel,
    requester: (Int) -> FocusRequester,
) {
    val favourites = model.favourites
    SettingsSection(FAVOURITES_SECTION)
    favourites.forEachIndexed { index, favourite ->
        NavRow(
            "${index + 1}. ${favourite.name}",
            favouriteSummary(favourite),
            onClick = { model.openFavourite(favourite.name) },
            modifier = Modifier.focusRequester(requester(index)),
        )
    }
    NavRow(
        SAVE_FAVOURITE,
        if (favourites.isEmpty()) NO_FAVOURITES else "Saves the display chain under a name",
        onClick = model::startSaving,
        modifier = Modifier.focusRequester(requester(favourites.size)),
    )
}

/**
 * "Not installed: a, b" when the favourite's shaders aren't all here, "No shaders" for an empty chain, else its shaders
 * joined with " · ".
 */
fun favouriteSummary(favourite: FavouriteInfo): String =
    when {
        favourite.missing.isNotEmpty() -> "Not installed: ${favourite.missing.joinToString(", ")}"
        favourite.shaders.isEmpty() -> "No shaders"
        else -> favourite.shaders.joinToString(" · ")
    }

/** The question [question] asks, in the design's words. */
fun favouriteQuestionText(question: FavouriteQuestion): String =
    when (question) {
        is FavouriteQuestion.Apply ->
            if (question.stages == 1) {
                "Apply ${question.name}? It replaces the shader in the chain."
            } else {
                "Apply ${question.name}? It replaces the ${question.stages} shaders in the chain."
            }
        is FavouriteQuestion.Replace -> "A favourite named ${question.name} already exists. Replace it?"
        is FavouriteQuestion.Delete -> "Delete the favourite ${question.name}?"
    }

/**
 * What shows over the chains page for the favourites, if anything: a question, the name form, or the open
 * favourite's panel (Apply first and focused, Rename…, Move up, Move down, Delete). B drops the question; in the name
 * form it goes back to the favourite's panel when renaming, else to "Save as favourite"; else it closes the panel, back
 * to its row.
 */
@Composable
fun FavouritePanel(
    model: PostProcessingModel,
    modifier: Modifier = Modifier,
) {
    val question = model.favouriteQuestion
    val naming = model.naming
    val name = model.favourite
    val favourite = model.favourites.firstOrNull { it.name == name }
    // The name as typed, here rather than in the form, which a question replaces for a while: it comes back as it was.
    val text = remember(naming != null, naming?.renaming) { TextFieldState(naming?.renaming.orEmpty()) }
    if (question == null && naming == null && favourite == null) return
    OverlayPanel(
        onBack = {
            when {
                question != null -> model.cancelFavourite()
                naming != null -> model.cancelNaming()
                else -> model.closeFavourite()
            }
        },
        title = favourite?.name ?: "Favourites",
        modifier = modifier,
    ) {
        when {
            question != null -> QuestionContent(model, question)
            naming != null -> NameForm(model, naming, text)
            favourite != null -> FavouriteActions(model, favourite)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionContent(
    model: PostProcessingModel,
    question: FavouriteQuestion,
) {
    val (confirm, kind) =
        when (question) {
            is FavouriteQuestion.Apply -> "Apply" to ButtonKind.Primary
            is FavouriteQuestion.Replace -> "Replace" to ButtonKind.Danger
            is FavouriteQuestion.Delete -> "Delete" to ButtonKind.Danger
        }
    Question(
        favouriteQuestionText(question),
        confirm,
        onConfirm = model::confirmFavourite,
        onCancel = model::cancelFavourite,
        confirmKind = kind,
        key = question,
    )
}

/**
 * The name form: its title, the Name field (focused) holding [text], the core's refusal if any, then Save (or Rename)
 * and Cancel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameForm(
    model: PostProcessingModel,
    naming: Naming,
    text: TextFieldState,
) {
    val colors = GooseStationTheme.colors
    val field = remember { FocusRequester() }
    LaunchedEffect(naming.renaming) { field.requestFocus() }
    val submit = { model.submitName(text.text.toString()) }
    Text(
        if (naming.renaming == null) SAVE_FAVOURITE else "Rename",
        style = MaterialTheme.typography.headlineSmall,
        color = colors.ink,
    )
    TextFieldRow("Name", text, modifier = Modifier.focusRequester(field), imeAction = ImeAction.Done, onDone = submit)
    if (naming.error.isNotEmpty()) {
        Notice(NoticeStatus.Danger) {
            Text(naming.error, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        }
    }
    ButtonRow {
        ActionButton(if (naming.renaming == null) "Save" else "Rename", onClick = submit, kind = ButtonKind.Primary)
        ActionButton("Cancel", onClick = model::cancelNaming, kind = ButtonKind.Ghost)
    }
}

private enum class FavouriteButton { Apply, Up, Down }

/** The favourite's name and shaders, then its actions; after a move the focus stays on the button while it can. */
@Composable
private fun FavouriteActions(
    model: PostProcessingModel,
    favourite: FavouriteInfo,
) {
    val colors = GooseStationTheme.colors
    val index = model.favourites.indexOf(favourite)
    val canUp = index > 0
    val canDown = index < model.favourites.lastIndex
    val buttons = remember { FavouriteButton.entries.associateWith { FocusRequester() } }
    var pressed by remember { mutableStateOf<FavouriteButton?>(null) }
    LaunchedEffect(favourite.name, index) {
        // Apply as the panel opens; after a move, the button pressed while it still can, else the other way.
        val target =
            when (pressed) {
                FavouriteButton.Up -> if (canUp) FavouriteButton.Up else FavouriteButton.Down
                FavouriteButton.Down -> if (canDown) FavouriteButton.Down else FavouriteButton.Up
                else -> FavouriteButton.Apply
            }
        runCatching { buttons.getValue(target).requestFocus() }
    }
    // Apply is the first thing that takes focus: as it does, the name over it shows too, so going back up a panel
    // that scrolls (a short screen) comes back to its top.
    val header = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(Modifier.bringIntoViewRequester(header), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(favourite.name, style = MaterialTheme.typography.headlineSmall, color = colors.ink)
        Text(favouriteSummary(favourite), style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
    }
    model.favouriteError?.let {
        Notice(NoticeStatus.Danger) { Text(it, style = MaterialTheme.typography.titleMedium, color = colors.ink) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val wide = Modifier.fillMaxWidth()
        ActionButton(
            "Apply",
            onClick = { model.apply(favourite.name) },
            modifier =
                wide
                    .focusRequester(buttons.getValue(FavouriteButton.Apply))
                    .onFocusChanged { if (it.isFocused) scope.launch { header.bringIntoView() } },
            kind = ButtonKind.Primary,
        )
        ActionButton("Rename…", onClick = { model.startRenaming(favourite.name) }, modifier = wide)
        ActionButton(
            "Move up",
            onClick = {
                pressed = FavouriteButton.Up
                model.moveFavourite(favourite.name, down = false)
            },
            modifier = wide.focusRequester(buttons.getValue(FavouriteButton.Up)),
            enabled = canUp,
        )
        ActionButton(
            "Move down",
            onClick = {
                pressed = FavouriteButton.Down
                model.moveFavourite(favourite.name, down = true)
            },
            modifier = wide.focusRequester(buttons.getValue(FavouriteButton.Down)),
            enabled = canDown,
        )
        ActionButton(
            "Delete",
            onClick = { model.deleteFavourite(favourite.name) },
            modifier = wide,
            kind = ButtonKind.Danger,
        )
    }
}
