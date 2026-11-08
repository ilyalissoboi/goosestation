// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.library.LibrarySearch
import io.github.ilyalissoboi.goosestation.library.PlayTime
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsColors
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/** The size of a game's cover in the list. */
private val THUMB = 48.dp

/**
 * A game's region, serial (unless not [serial]) and play time, joined; a game without one leaves it out. A PS-EXE, or
 * a disc the core's database doesn't know and with no serial, shows the core's content-hash ID (`HASH-…`) as its serial.
 */
internal fun gameDetails(
    game: GameEntry,
    serial: Boolean = true,
): String =
    listOf(game.region, if (serial) game.serial else "", PlayTime.format(game.totalPlayedSeconds))
        .filter { it.isNotBlank() }
        .joinToString(" · ")

/** [text] with the first match of the search's [query] marked, `beak-ink` on `beak-soft`; as it is without one. */
internal fun marked(
    text: String,
    query: String?,
    colors: GsColors,
): AnnotatedString =
    buildAnnotatedString {
        append(text)
        LibrarySearch.match(text, query)?.let {
            addStyle(SpanStyle(color = colors.beakInk, background = colors.beakSoft), it.first, it.last + 1)
        }
    }

/** [gameDetails], with a match of the search's [query] in the serial marked, as [marked] marks it. */
private fun markedDetails(
    game: GameEntry,
    query: String?,
    colors: GsColors,
): AnnotatedString {
    val details = gameDetails(game)
    val match = LibrarySearch.match(game.serial, query) ?: return AnnotatedString(details)
    val at = details.indexOf(game.serial).takeIf { it >= 0 } ?: return AnnotatedString(details)
    return buildAnnotatedString {
        append(details)
        addStyle(SpanStyle(color = colors.beakInk, background = colors.beakSoft), at + match.first, at + match.last + 1)
    }
}

/**
 * A game in the list: its cover, small, then its title, and its region, serial and play time under it, a match of the
 * search's [query] marked in the title and the serial. A, Enter or a tap call [onClick]; a long press, [onLongClick],
 * if any.
 */
@Composable
fun GameListRow(
    game: GameEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    query: String? = null,
) {
    val colors = GooseStationTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(onLongClick = onLongClick, onClick = onClick)
                .padding(start = 8.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(THUMB).clip(GsShapes.small).background(colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            val targetPx = with(LocalDensity.current) { THUMB.roundToPx() }
            val cover by produceState<ImageBitmap?>(null, game.coverPath, targetPx) {
                value = game.coverPath?.let { covers.load(it, targetPx) }
            }
            cover?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } ?: Icon(GsIcons.disc, contentDescription = null, tint = colors.inkFaint, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                marked(game.title, query, colors),
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                markedDetails(game, query, colors),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
