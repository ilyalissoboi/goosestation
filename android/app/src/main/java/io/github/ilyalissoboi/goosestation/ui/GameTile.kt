// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.library.CoverLoader
import io.github.ilyalissoboi.goosestation.ui.components.focusIndicator
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

// For the life of the process, so covers stay decoded across screens: an eighth of the heap.
internal val covers = CoverLoader((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

/**
 * A game in the grid: its cover, fitted into a square, or the disc icon, its title and serial there until it has one;
 * then the title and a line of its region and play time under it. Focus rings the cover, so the art stays the hero. A,
 * Enter or a tap call [onClick].
 */
@Composable
fun GameTile(
    game: GameEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier =
            modifier.focusRing(focusedFill = null, ring = false, onFocusChange = { focused = it }, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .focusIndicator(focused, colors.focus, GsShapes.small)
                    .clip(GsShapes.small)
                    .background(colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            val targetPx = constraints.maxWidth
            val cover by produceState<ImageBitmap?>(null, game.coverPath, targetPx) {
                value = game.coverPath?.let { covers.load(it, targetPx) }
            }
            cover?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } ?: Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(GsIcons.disc, contentDescription = null, tint = colors.inkFaint, modifier = Modifier.size(24.dp))
                Text(
                    game.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.ink,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (game.serial.isNotBlank()) {
                    Text(game.serial, style = GooseStationTheme.type.mono, color = colors.inkMuted, maxLines = 1)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                game.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                gameDetails(game, serial = false),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
