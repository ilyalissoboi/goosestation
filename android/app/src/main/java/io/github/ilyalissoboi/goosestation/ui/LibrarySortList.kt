// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.library.LibrarySort
import io.github.ilyalissoboi.goosestation.library.LibraryWords
import io.github.ilyalissoboi.goosestation.library.SortKey
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.ChoiceList

/** The sort list's width, less on a narrower screen. */
private val LIST_WIDTH = 240.dp

/** Its room from the screen's edges, and from the Sort button over it. */
private val EDGE = 8.dp

/**
 * The library's sorts, open under the Sort button ([anchor], in this layout's coordinates), drawn in the screen
 * itself, never a window: the design system's choice list, the current sort marked and focused. It's the sorts the
 * library offers, then the current one if settings.ini has one it doesn't (Type, Uncompressed size), shown by its
 * name. Its right edge is the button's, kept inside the screen, and it scrolls when there isn't room under the button.
 * A picks ([onPick]); B, Back or a tap outside close it unchanged ([onClose]). For a [androidx.compose.foundation.layout.Box]
 * over the whole screen.
 */
@Composable
internal fun LibrarySortList(
    sort: LibrarySort,
    anchor: Rect,
    onPick: (SortKey) -> Unit,
    onClose: () -> Unit,
) {
    val keys = if (sort.key in SortKey.OFFERED) SortKey.OFFERED else SortKey.OFFERED + sort.key
    val choices = keys.map { Choice(it, LibraryWords.sortName(it)) }
    BackHandler(onBack = onClose)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Under the list: a tap anywhere else closes it, and does nothing else.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } })
        val (right, bottom) = with(LocalDensity.current) { anchor.right.toDp() to anchor.bottom.toDp() }
        val width = LIST_WIDTH.coerceAtMost(maxWidth - EDGE * 2)
        val x = (right - width).coerceIn(EDGE, (maxWidth - width - EDGE).coerceAtLeast(EDGE))
        val top = bottom + EDGE
        Box(
            Modifier
                .offset(x, top)
                .width(width)
                .heightIn(max = (maxHeight - top - EDGE).coerceAtLeast(48.dp))
                .verticalScroll(rememberScrollState())
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.ButtonB && event.key != Key.Back) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) onClose()
                    true
                },
        ) {
            ChoiceList(choices, selected = sort.key, onPick = { onPick(it.value) })
        }
    }
}
