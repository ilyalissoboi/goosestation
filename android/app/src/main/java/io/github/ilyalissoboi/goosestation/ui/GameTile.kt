// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * then the title, a match of the search's [query] marked in it, and a line of its region and play time under it. Focus
 * rings the cover, so the art stays the hero. A, Enter or a tap call [onClick]; a long press, [onLongClick], if any.
 *
 * The cover is as wide as the tile's column, unless a whole tile (the cover, two lines of title and the details line)
 * would be taller than [fitHeight], the grid's room for a row: then it's smaller, still square, and the tile is centred
 * in its column ([coverSize]). A tile is that tall with a one-line title too, the room left under its details line.
 */
@Composable
fun GameTile(
    game: GameEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    query: String? = null,
    fitHeight: Dp = Dp.Infinity,
) {
    val colors = GooseStationTheme.colors
    var focused by remember { mutableStateOf(false) }
    val titleStyle = MaterialTheme.typography.titleMedium
    val detailsStyle = MaterialTheme.typography.bodyMedium
    // What goes under the cover, as the text lays out at its size: the gap, two lines of title, and the details line.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textHeight =
        remember(titleStyle, detailsStyle, density) {
            val title = measurer.measure("A\nA", titleStyle, maxLines = 2).size.height
            val details = measurer.measure("A", detailsStyle, maxLines = 1).size.height
            with(density) { TITLE_GAP + title.toDp() + DETAILS_GAP + details.toDp() }
        }
    BoxWithConstraints(
        modifier =
            modifier.focusRing(
                focusedFill = null,
                ring = false,
                onFocusChange = { focused = it },
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        val side = coverSize(maxWidth, fitHeight, textHeight)
        // Room for two lines of title whatever the title: every tile in a row is as tall, so the grid, which centres the
        // focused one, doesn't move the row as the focus goes from a one-line title to a two-line one.
        Column(
            Modifier.width(side).heightIn(min = side + textHeight),
            verticalArrangement = Arrangement.spacedBy(TITLE_GAP),
        ) {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .size(side)
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
                    Image(
                        it,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } ?: Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        GsIcons.disc,
                        contentDescription = null,
                        tint = colors.inkFaint,
                        modifier = Modifier.size(24.dp),
                    )
                    // A small cover keeps the disc alone: the title is under it anyway, and a serial wouldn't fit.
                    if (side >= ROOMY_COVER) {
                        Text(
                            game.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.ink,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            // A long title gives way, a line at a time, so the serial under it always fits the square.
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (game.serial.isNotBlank()) {
                            Text(
                                game.serial,
                                style = GooseStationTheme.type.mono,
                                color = colors.inkMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(DETAILS_GAP)) {
                Text(
                    marked(game.title, query, colors),
                    style = titleStyle,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    gameDetails(game, serial = false),
                    style = detailsStyle,
                    color = colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The gap between a tile's cover and its title, and between the title and the details line. */
private val TITLE_GAP = 8.dp
private val DETAILS_GAP = 2.dp

/** The least a cover shrinks to on a short screen: under it, the grid scrolls to show a whole tile. */
private val MIN_COVER = 96.dp

/** The least cover that shows the title and the serial, under the disc, until the art loads. */
private val ROOMY_COVER = 140.dp

/**
 * A tile's cover side: its column's [cellWidth], or less when a whole tile, the cover and [textHeight] under it, would
 * be taller than [rowHeight], but never under [MIN_COVER].
 */
internal fun coverSize(
    cellWidth: Dp,
    rowHeight: Dp,
    textHeight: Dp,
): Dp = minOf(cellWidth, maxOf(MIN_COVER, rowHeight - textHeight))
