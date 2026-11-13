// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/**
 * A place in a rail of them, as the design system's nav rail has it (the settings' categories): its [icon] and
 * [label], `ink-muted` at rest. The [current] one is on `beak-soft`, its label `ink` and its icon `beak-ink`; a
 * focused one wears the focus ring, on `raised`, in the same inks. A, Enter, the D-pad centre or a tap call
 * [onClick]; [onFocusChange] hears the focus come and go. A disabled one is dimmed, and can't be focused or picked.
 * A screen reader hears a tab, selected while it's the current one.
 */
@Composable
fun RailItem(
    label: String,
    icon: ImageVector,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    val colors = GooseStationTheme.colors
    var focused by remember { mutableStateOf(false) }
    val lit = current || focused
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(
                    enabled = enabled,
                    role = Role.Tab,
                    // The current one keeps its own ground under the ring.
                    focusedFill = if (current) null else colors.raised,
                    onFocusChange = {
                        focused = it
                        onFocusChange?.invoke(it)
                    },
                    onClick = onClick,
                ).semantics { selected = current }
                .background(if (current) colors.beakSoft else Color.Transparent, GsShapes.medium)
                // The touch size.
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint =
                when {
                    !enabled -> colors.inkFaint
                    lit -> colors.beakInk
                    else -> colors.inkMuted
                },
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color =
                when {
                    !enabled -> colors.inkFaint
                    lit -> colors.ink
                    else -> colors.inkMuted
                },
            modifier = Modifier.weight(1f),
        )
    }
}
