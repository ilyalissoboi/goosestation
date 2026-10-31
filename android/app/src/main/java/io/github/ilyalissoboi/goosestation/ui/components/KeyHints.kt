// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.input.BindingLabels
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/** One button hint: the controller button's [glyph], as its key chip shows it, and the [action] it takes. */
data class KeyHint(
    val glyph: String,
    val action: String,
)

/** The glyphs of the buttons the hints name, as the Controllers page labels the pad's buttons. */
object PadGlyphs {
    /** The button that activates: KEYCODE_BUTTON_A. */
    val confirm: String = BindingLabels.label("Button96")

    /** The button that goes back: KEYCODE_BUTTON_B. */
    val back: String = BindingLabels.label("Button97")
}

/**
 * The button-hint footer: each of [hints] as a key chip with its glyph, then the action it takes, in a row that wraps
 * on a narrow screen. It goes at a panel's foot, naming the buttons with [PadGlyphs]. It takes no input: nothing in it
 * is focusable or clickable, and each hint reads as "glyph: action".
 */
@Composable
fun KeyHints(
    hints: List<KeyHint>,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { hint ->
            Row(
                modifier = Modifier.clearAndSetSemantics { contentDescription = "${hint.glyph}: ${hint.action}" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                            .border(1.dp, colors.lineStrong, GsShapes.small)
                            .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(hint.glyph, style = GooseStationTheme.type.key, color = colors.ink)
                }
                Text(hint.action, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
            }
        }
    }
}
