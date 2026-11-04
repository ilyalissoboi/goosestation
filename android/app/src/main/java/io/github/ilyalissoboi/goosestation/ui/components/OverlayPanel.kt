// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/**
 * A small panel over the screen, drawn in the app's window (never a window dialog): a `scrim` over all of it, past the
 * system bars, that takes every tap, and a centred `surface` panel at `radius-lg`, at most 420 dp wide, inside the safe
 * area, that scrolls. The focus can't leave it; the caller focuses what it holds. B, Back and the system's Back call
 * [onBack], B and Back on the key-up of a press that began in it, so a press held from the screen under it doesn't
 * close it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OverlayPanel(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    val colors = GooseStationTheme.colors
    // The keys whose key-down the panel has seen, and whose key-up hasn't come yet.
    val down = remember { HashSet<Key>() }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .pointerInput(Unit) { detectTapGestures {} }
                .onKeyEvent { event ->
                    val key = event.key
                    if (key != Key.Back && key != Key.ButtonB) return@onKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> down += key
                        KeyEventType.KeyUp -> if (down.remove(key)) onBack()
                    }
                    true
                }.safeDrawingPadding()
                .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The panel scrolls only as far as it must: a focused text field asks to show its cursor as well as itself, and
        // the screens' centring can't do both, so it would never settle.
        CompositionLocalProvider(LocalBringIntoViewSpec provides LeastScrolling) {
            Column(
                modifier =
                    modifier
                        .focusProperties { onExit = { cancelFocusChange() } }
                        .focusGroup()
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .background(colors.surface, GsShapes.large)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

/** Scrolls the least that shows what's asked: nothing if it shows already, else its nearer edge, or its start. */
@OptIn(ExperimentalFoundationApi::class)
private object LeastScrolling : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float =
        when {
            offset >= 0f && offset + size <= containerSize -> 0f
            size > containerSize || offset < 0f -> offset
            else -> offset + size - containerSize
        }
}
