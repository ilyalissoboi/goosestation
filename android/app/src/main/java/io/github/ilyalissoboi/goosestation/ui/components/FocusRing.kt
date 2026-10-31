// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/** The focus ring's width, and its gap from the element, so it never touches the element's fill. */
private val FOCUS_WIDTH = 2.dp
private val FOCUS_OFFSET = 2.dp

/**
 * Draws the focus ring around an element while [focused]: a `focus` outline of [shape], [FOCUS_OFFSET] outside its
 * edge. Being outside, it needs that much room around the element, or a clipping parent cuts it.
 */
fun Modifier.focusIndicator(
    focused: Boolean,
    color: Color,
    shape: Shape = GsShapes.medium,
): Modifier =
    drawBehind {
        if (!focused) return@drawBehind
        val stroke = FOCUS_WIDTH.toPx()
        val inset = FOCUS_OFFSET.toPx() + stroke / 2
        val outline = shape.createOutline(Size(size.width + 2 * inset, size.height + 2 * inset), layoutDirection, this)
        translate(-inset, -inset) { drawOutline(outline, color, style = Stroke(stroke)) }
    }

/**
 * Makes an element focusable and clickable for a D-pad: A, Enter, the D-pad centre or a tap call [onClick]. A
 * disabled element can't take focus. While focused it wears the focus ring, unless [ring] is off (the element draws
 * it on a part of itself, told by [onFocusChange]), and [focusedFill] under its content, if any. It's drawn above its
 * siblings while focused, so they don't cover the ring.
 */
@Composable
fun Modifier.focusRing(
    enabled: Boolean = true,
    role: Role? = null,
    shape: Shape = GsShapes.medium,
    focusedFill: Color? = GooseStationTheme.colors.raised,
    ring: Boolean = true,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    // From the focus itself, not clickable()'s interactions: focus that comes as the element is composed (Compose
    // moves it there when the focused element goes away) is emitted before anything collects them, and would be missed.
    var focused by remember { mutableStateOf(false) }
    // Only an A pressed on this element activates it, not the release of one held since the previous screen.
    var buttonADown by remember { mutableStateOf(false) }
    val focusColor = GooseStationTheme.colors.focus

    return this
        // Above its neighbours while focused, so the next element's fill doesn't cover the ring's edge.
        .zIndex(if (focused) 1f else 0f)
        // A controller's A button; clickable() handles Enter and the D-pad centre.
        .onFocusChanged {
            if (focused != it.isFocused) onFocusChange?.invoke(it.isFocused)
            focused = it.isFocused
            if (!it.isFocused) buttonADown = false
        }.onKeyEvent { event ->
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
        // clickable() only takes focus out of touch mode; screens are focused for a controller from the start.
        .focusProperties { canFocus = enabled }
        .clickable(
            interactionSource = null,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        ).focusIndicator(focused && ring, focusColor, shape)
        .background(if (focused && focusedFill != null) focusedFill else Color.Transparent, shape)
}

/** What a button is for, which sets how loud it is. */
enum class ButtonKind {
    /** The one thing the screen is for: Start, Next, Resume. Once a screen. */
    Primary,

    /** Every other command. */
    Secondary,

    /** Low-stakes commands beside a primary one: Back, Cancel, Remove. */
    Ghost,

    /** Deletes or overwrites. */
    Danger,
}

/** A button with the [focusRing]: [text], an optional leading [icon], in its [kind]'s colours. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: ButtonKind = ButtonKind.Secondary,
    icon: ImageVector? = null,
) {
    val colors = GooseStationTheme.colors
    val (fill, edge, ink) =
        when {
            !enabled -> Triple(colors.surface, colors.line, colors.inkFaint)
            kind == ButtonKind.Primary -> Triple(colors.beak, Color.Transparent, colors.onBeak)
            kind == ButtonKind.Secondary -> Triple(colors.raised, colors.line, colors.ink)
            kind == ButtonKind.Danger -> Triple(colors.raised, colors.line, colors.danger)
            else -> Triple(Color.Transparent, Color.Transparent, colors.ink)
        }
    Row(
        modifier =
            modifier
                .focusRing(enabled = enabled, role = Role.Button, focusedFill = null, onClick = onClick)
                .background(fill, GsShapes.medium)
                .border(1.dp, edge, GsShapes.medium)
                // The touch and TV size: 48 dp tall at least, 24 dp each side.
                .heightIn(min = 48.dp)
                .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp)) }
        Text(text = text, style = MaterialTheme.typography.titleMedium, color = ink)
    }
}
