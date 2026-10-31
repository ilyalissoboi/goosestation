// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.ui.components.Eyebrow
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/**
 * The paused game's menu, over the game in the same window: "Paused" over the [game]'s title and details, if it's
 * known, then **Resume**, focused, and **Quit to library**. B, Back and Mode resume too, on the key-up of a press that
 * began here: the rest of the press that opened the menu does nothing.
 */
@Composable
fun GamePrompt(
    onResume: () -> Unit,
    onQuit: () -> Unit,
    game: GameEntry? = null,
) {
    // Back from the system, and B and Back from the card below, which go through the back handlers, this one first.
    BackHandler(onBack = onResume)
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // The keys whose key-down the card has seen, and whose key-up hasn't come yet.
    val down = remember { HashSet<Key>() }
    val resume = remember { FocusRequester() }
    LaunchedEffect(Unit) { resume.requestFocus() }
    val colors = GooseStationTheme.colors

    Box(
        modifier = Modifier.fillMaxSize().background(colors.scrim).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .onKeyEvent { event ->
                        val key = event.key
                        if (key != Key.Back && key != Key.ButtonB && key != Key.ButtonMode) return@onKeyEvent false
                        when (event.type) {
                            KeyEventType.KeyDown -> down += key
                            KeyEventType.KeyUp ->
                                if (down.remove(key)) {
                                    if (key == Key.ButtonMode) onResume() else dispatcher?.onBackPressed() ?: onResume()
                                }
                        }
                        true
                    }.widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .background(colors.surface, GsShapes.large)
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow("Paused")
                game?.let {
                    Text(
                        it.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(gameDetails(it), style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MenuItem("Resume", GsIcons.play, onResume, Modifier.focusRequester(resume))
                MenuItem("Quit to library", GsIcons.power, onQuit)
            }
        }
    }
}

/** One of the menu's items: [icon] and [label]; focused, the row lifts and the icon takes the beak's ink. */
@Composable
private fun MenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(role = Role.Button, onFocusChange = { focused = it }, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) colors.beakInk else colors.inkMuted,
            modifier = Modifier.size(20.dp),
        )
        Text(label, style = MaterialTheme.typography.titleMedium, color = colors.ink)
    }
}
