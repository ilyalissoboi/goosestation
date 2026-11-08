// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The test tag of the label an [ActionLabelStrip] shows. */
const val ACTION_LABEL = "actionLabel"

/** How long a long press shows a button's label. */
private const val TOUCH_LABEL_MS = 2_000L

/**
 * Which [IconActionButton]'s label shows, and where that button is, for an [ActionLabelStrip] to draw it under the
 * button. A group of buttons shares one, so only one label shows at a time.
 */
@Stable
class ActionLabelHost {
    internal class Shown(
        val owner: Any,
        val label: String,
        val anchor: Rect,
    )

    internal var shown: Shown? by mutableStateOf(null)
        private set

    internal fun show(
        owner: Any,
        label: String,
        anchor: Rect,
    ) {
        shown = Shown(owner, label, anchor)
    }

    internal fun hide(owner: Any) {
        if (shown?.owner === owner) shown = null
    }
}

@Composable
fun rememberActionLabelHost(): ActionLabelHost = remember { ActionLabelHost() }

/**
 * A command as an icon alone: 48 dp square, `raised` on a `line` edge, the [icon] in `ink-muted`, `beak-ink` while
 * focused, with the [focusRing]. Its [label] is its accessible name, and shows in [labels]' strip while it's focused,
 * or for two seconds after a long press (a tap acts, [onClick]). For the library's top bar, where words wouldn't fit.
 */
@Composable
fun IconActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    labels: ActionLabelHost,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val colors = GooseStationTheme.colors
    val owner = remember { Any() }
    var focused by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    var longPresses by remember { mutableIntStateOf(0) }
    var touchShown by remember { mutableStateOf(false) }
    LaunchedEffect(longPresses) {
        if (longPresses == 0) return@LaunchedEffect
        touchShown = true
        delay(TOUCH_LABEL_MS)
        touchShown = false
    }
    LaunchedEffect(focused, touchShown, label, bounds) {
        val anchor = bounds
        if ((focused || touchShown) && anchor != null) labels.show(owner, label, anchor) else labels.hide(owner)
    }
    DisposableEffect(labels) { onDispose { labels.hide(owner) } }
    Box(
        modifier =
            modifier
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .focusRing(
                    role = Role.Button,
                    focusedFill = null,
                    onFocusChange = {
                        focused = it
                        onFocusChange(it)
                    },
                    onLongClick = { longPresses++ },
                    onClick = onClick,
                ).semantics { contentDescription = label }
                .size(48.dp)
                .background(colors.raised, GsShapes.medium)
                .border(1.dp, colors.line, GsShapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) colors.beakInk else colors.inkMuted,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Where [labels]' buttons show their labels: a strip one label high whether or not one shows, under the buttons. The
 * label, `body-sm` `ink` on `raised` with a `line` edge, is centred under its button and kept inside the strip. It's
 * hidden from screen readers, which hear the button's own name.
 */
@Composable
fun ActionLabelStrip(
    labels: ActionLabelHost,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    // A label's padding and edge round its line, and a 2 dp gap above it.
    val height = with(density) { style.lineHeight.toDp() } + 8.dp
    var origin by remember { mutableStateOf(Offset.Zero) }
    val shown = labels.shown
    Layout(
        content = {
            if (shown != null) {
                Text(
                    shown.label,
                    style = style,
                    color = colors.ink,
                    maxLines = 1,
                    softWrap = false,
                    modifier =
                        Modifier
                            .testTag(ACTION_LABEL)
                            .semantics { hideFromAccessibility() }
                            .background(colors.raised, GsShapes.small)
                            .border(1.dp, colors.line, GsShapes.small)
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        },
        modifier = modifier.onGloballyPositioned { origin = it.positionInRoot() },
    ) { measurables, constraints ->
        val pill = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else pill?.width ?: 0
        layout(width, height.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)) {
            if (pill != null && shown != null) {
                val centre = shown.anchor.center.x - origin.x
                val x = (centre - pill.width / 2f).roundToInt().coerceIn(0, (width - pill.width).coerceAtLeast(0))
                pill.place(x, 2.dp.roundToPx())
            }
        }
    }
}
