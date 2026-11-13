// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes
import io.github.ilyalissoboi.goosestation.ui.theme.Motion

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
    // The scrim and panel fade in over Motion.DIALOG_MS; what they hold is there, and focused, from the first frame.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(Motion.DIALOG_MS)) }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value }
                .background(colors.scrim)
                .pointerInput(Unit) { detectTapGestures {} }
                .backKeys(preview = false, onBack = onBack)
                .safeDrawingPadding()
                .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The panel scrolls only as far as it must: a focused text field asks to show its cursor as well as itself, and
        // the screens' centring can't do both, so it would never settle. What it scrolls to keeps the panel's padding
        // beside it, so the last button isn't against the panel's edge.
        val density = LocalDensity.current
        val scrolling = remember(density) { LeastScrolling(with(density) { PANEL_PADDING.toPx() }) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides scrolling) {
            Column(
                modifier =
                    modifier
                        .focusProperties { onExit = { cancelFocusChange() } }
                        .focusGroup()
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .background(colors.surface, GsShapes.large)
                        .verticalScroll(rememberScrollState())
                        .padding(PANEL_PADDING),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

private val PANEL_PADDING = 24.dp

/**
 * Scrolls the least that shows what's asked with [margin] beside it: nothing if that shows already, else its nearer
 * edge, or its start. The scroll's own bounds stop it short at either end.
 */
@OptIn(ExperimentalFoundationApi::class)
private class LeastScrolling(
    private val margin: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float =
        when {
            offset >= margin && offset + size + margin <= containerSize -> 0f
            size + 2 * margin > containerSize || offset < margin -> offset - margin
            else -> offset + size + margin - containerSize
        }
}
