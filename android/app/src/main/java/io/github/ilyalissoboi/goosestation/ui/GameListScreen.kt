// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * The games in [gamesFolder], one per row. The first row has focus, so the list works with a D-pad: A, Enter or a tap
 * starts a game, once [ready]. [message] is shown above the list: the latest error, or what the core is doing.
 */
@Composable
fun GameListScreen(
    games: List<File>,
    gamesFolder: File,
    ready: Boolean,
    message: String?,
    isError: Boolean,
    onStartGame: (File) -> Unit,
) {
    val firstItem = remember { FocusRequester() }
    // Rows only take focus once they're enabled.
    LaunchedEffect(games, ready) {
        if (ready && games.isNotEmpty()) firstItem.requestFocus()
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

        if (message != null) MessageBar(message, isError)

        if (games.isEmpty()) {
            Text(
                "No games found. Copy games to ${gamesFolder.absolutePath}.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun GameRow(game: File, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // Only an A pressed on this row starts it, not the release of one held since the game screen.
    var buttonADown by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)

    Text(
        text = game.name,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .fillMaxWidth()
                // A controller's A button; clickable() handles Enter and the D-pad centre.
                .onFocusChanged { if (!it.isFocused) buttonADown = false }
                .onKeyEvent { event ->
                    if (event.key != Key.ButtonA) return@onKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> buttonADown = true
                        KeyEventType.KeyUp -> {
                            if (buttonADown && enabled) onClick()
                            buttonADown = false
                        }
                    }
                    true
                }
                // clickable() only takes focus out of touch mode; the list is focused for a controller from the start.
                .focusProperties { canFocus = enabled }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ).background(if (focused) colors.surfaceVariant else Color.Transparent, shape)
                .border(2.dp, if (focused) colors.primary else Color.Transparent, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
