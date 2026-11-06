// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.GamePage
import io.github.ilyalissoboi.goosestation.app.GameQuestion
import io.github.ilyalissoboi.goosestation.app.GameSummary
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.GameContext
import io.github.ilyalissoboi.goosestation.ui.components.LocalGameContext
import io.github.ilyalissoboi.goosestation.ui.components.NavRow
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

/**
 * A game's settings hub, "Game settings" under the [game]'s title: its serial and region, what its file sets
 * ([summary]: how many of its pages' settings, and any it has that no page shows), an entry per page ([onOpen]), each
 * saying how many it sets, then Copy global settings and Clear settings. Each asks first ([onAsk]), in a panel over the
 * hub while [asking]: its answer ([onConfirm]) first, Cancel ([onCancel]) focused; the focus goes back to its button.
 * The first entry, or [focusEntry] (the page just left), is focused on opening. B or Back go back ([onBack]).
 */
@Composable
fun GameSettingsScreen(
    game: GameEntry,
    summary: GameSummary?,
    focusEntry: GamePage?,
    asking: GameQuestion?,
    onOpen: (GamePage) -> Unit,
    onAsk: (GameQuestion) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = GooseStationTheme.colors
    val entries = remember { GamePage.entries.associateWith { FocusRequester() } }
    val buttons = remember { GameQuestion.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { entries.getValue(focusEntry ?: GamePage.entries.first()).requestFocus() }
    // The button whose question shows: the focus goes back to it once the panel, which holds the focus, has gone.
    var askedBy by remember { mutableStateOf<GameQuestion?>(null) }
    LaunchedEffect(asking) {
        if (asking == null) {
            askedBy?.let { buttons.getValue(it).requestFocus() }
            askedBy = null
        }
    }
    val ask = { question: GameQuestion ->
        askedBy = question
        onAsk(question)
    }
    val context = LocalGameContext.current ?: GameContext(game.title, running = false, hardcoreActive = false)

    CompositionLocalProvider(LocalGameContext provides context) {
        SettingsPage("Game settings", onBack = onBack) {
            Text(
                listOf(game.serial, game.region).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            summary?.let {
                Text(
                    GameSettingsWords.summary(it),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.ink,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            GamePage.entries.forEach { page ->
                val count = summary?.counts?.get(page) ?: 0
                NavRow(
                    page.label,
                    if (count > 0) GameSettingsWords.pageCount(count) else page.summary,
                    onClick = { onOpen(page) },
                    modifier = Modifier.focusRequester(entries.getValue(page)),
                )
            }
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    "Copy global settings",
                    onClick = { ask(GameQuestion.Copy) },
                    modifier = Modifier.focusRequester(buttons.getValue(GameQuestion.Copy)),
                )
                ActionButton(
                    "Clear settings",
                    onClick = { ask(GameQuestion.Clear) },
                    modifier = Modifier.focusRequester(buttons.getValue(GameQuestion.Clear)),
                    kind = ButtonKind.Danger,
                )
            }
        }
    }

    asking?.let { GameQuestionPanel(it, game.title, summary?.others ?: 0, onConfirm, onCancel) }
}

/**
 * What a game's hub asks before Copy or Clear ([question]), for the game called [title] whose file has [others] keys
 * no page shows: over the hub, its answer ([onConfirm]) first, Cancel ([onCancel]) focused.
 */
@Composable
fun GameQuestionPanel(
    question: GameQuestion,
    title: String,
    others: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    OverlayPanel(onBack = onCancel) {
        val cancel = remember { FocusRequester() }
        LaunchedEffect(question) { cancel.requestFocus() }
        Text(
            GameSettingsWords.question(question, title, others),
            style = MaterialTheme.typography.bodyLarge,
            color = GooseStationTheme.colors.ink,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                if (question == GameQuestion.Copy) "Copy" else "Clear",
                onClick = onConfirm,
                kind = ButtonKind.Danger,
            )
            ActionButton(
                "Cancel",
                onClick = onCancel,
                modifier = Modifier.focusRequester(cancel),
                kind = ButtonKind.Ghost,
            )
        }
    }
}

/** What a game's hub says. */
object GameSettingsWords {
    /** "3 settings of its own, and 4 this app doesn't show", or that it has none. */
    fun summary(summary: GameSummary): String {
        val own =
            when (summary.total) {
                0 -> "No settings of its own"
                1 -> "1 setting of its own"
                else -> "${summary.total} settings of its own"
            }
        return if (summary.others > 0) "$own, and ${summary.others} this app doesn't show." else "$own."
    }

    fun pageCount(count: Int) = "$count set for this game"

    /** The question Copy or Clear asks, for the game called [title], whose file has [others] keys no page shows. */
    fun question(
        question: GameQuestion,
        title: String,
        others: Int,
    ): String =
        when (question) {
            GameQuestion.Copy ->
                "Copy the global settings to $title? Its own settings are replaced where the global ones are set."
            GameQuestion.Clear ->
                if (others > 0) {
                    "Clear $title's settings? Its file goes, with the $others settings this app doesn't show."
                } else {
                    "Clear $title's settings? It uses the global ones again."
                }
        }
}
