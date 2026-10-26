// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Makes an element focusable and clickable for a D-pad: a ring and a tint while it's focused, and A, Enter, the
 * D-pad centre or a tap call [onClick]. A disabled element can't take focus.
 */
@Composable
fun Modifier.focusRing(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    // From the focus itself, not clickable()'s interactions: focus that comes as the element is composed (Compose
    // moves it there when the focused element goes away) is emitted before anything collects them, and would be missed.
    var focused by remember { mutableStateOf(false) }
    // Only an A pressed on this element activates it, not the release of one held since the previous screen.
    var buttonADown by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)

    return this
        // A controller's A button; clickable() handles Enter and the D-pad centre.
        .onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) buttonADown = false
        }
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
        // clickable() only takes focus out of touch mode; screens are focused for a controller from the start.
        .focusProperties { canFocus = enabled }
        .clickable(
            interactionSource = null,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        ).background(if (focused) colors.surfaceVariant else Color.Transparent, shape)
        .border(2.dp, if (focused) colors.primary else Color.Transparent, shape)
}

/** A text button with the [focusRing]. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f),
        modifier =
            modifier
                .focusRing(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
