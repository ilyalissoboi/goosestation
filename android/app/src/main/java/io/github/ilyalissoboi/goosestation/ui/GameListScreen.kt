// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.ui.components.Banner
import io.github.ilyalissoboi.goosestation.ui.components.BannerModel
import io.github.ilyalissoboi.goosestation.ui.components.focusRing

/**
 * The games of the game list, one per row: until the library screen replaces it. The first row has focus, so the list
 * works with a D-pad: A, Enter or a tap starts a game, once [ready]. With no games, the first banner action has it.
 * [banners] and [message] are shown above the list: prompts to pick a folder again, the latest error, or what the
 * app is doing.
 */
@Composable
fun GameListScreen(
    games: List<GameEntry>,
    banners: List<BannerModel>,
    ready: Boolean,
    message: String?,
    isError: Boolean,
    onStartGame: (GameEntry) -> Unit,
) {
    val firstItem = remember { FocusRequester() }
    val firstAction = remember { FocusRequester() }
    val hasAction = banners.any { it.actions.isNotEmpty() }
    // Rows only take focus once they're enabled.
    LaunchedEffect(games, ready, hasAction) {
        if (ready && games.isNotEmpty()) {
            firstItem.requestFocus()
        } else if (hasAction) {
            firstAction.requestFocus()
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GooseStation", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)

        val firstWithAction = banners.indexOfFirst { it.actions.isNotEmpty() }
        banners.forEachIndexed { index, banner ->
            Banner(banner, firstAction = if (index == firstWithAction) firstAction else null)
        }
        if (message != null) MessageBar(message, isError)

        if (games.isEmpty()) {
            // Until the core runs, the message says what's happening instead.
            if (ready) {
                Text(
                    "No games found in your games folders.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Not lazy: every row exists as soon as it's composed, so the first one can take focus straight away.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                games.forEachIndexed { index, game ->
                    GameRow(
                        game = game,
                        enabled = ready,
                        modifier = if (index == 0) Modifier.focusRequester(firstItem) else Modifier,
                        onClick = { onStartGame(game) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBar(message: String, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = if (isError) colors.onErrorContainer else colors.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (isError) colors.errorContainer else colors.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun GameRow(game: GameEntry, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = game.title,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
