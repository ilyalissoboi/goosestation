// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/**
 * A panel's tabs: the L1 key chip, the [tabs]' labels, the [current] one on `beak-soft`, then R1. L1 and R1 switch
 * them (the panel handles the keys); a tap on a label selects it ([onSelect]). Nothing in it takes focus, so the D-pad
 * stays on the panel's rows.
 */
@Composable
fun PanelTabs(
    tabs: List<String>,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        KeyChip(PadGlyphs.previousTab)
        tabs.forEachIndexed { index, label ->
            val on = index == current
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (on) colors.ink else colors.inkMuted,
                modifier =
                    Modifier
                        .semantics {
                            role = Role.Tab
                            selected = on
                        }.pointerInput(index) { detectTapGestures { onSelect(index) } }
                        .background(if (on) colors.beakSoft else Color.Transparent, GsShapes.small)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .wrapContentSize(),
            )
        }
        KeyChip(PadGlyphs.nextTab)
    }
}
