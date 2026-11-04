// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.SyncBadge
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/**
 * Memory card sync's state as a badge: [badge]'s icon and word on its status's `-soft` ground, at `radius-sm`, the
 * icon at `icon-sm` in the status colour and the word in `label-sm` `ink`, so it reads without colour. Synced is
 * success, Syncing… info (the sync icon), Offline warning (cloud-off), conflicts and Needs setup warning, Sync failed
 * danger. Focusable, with the touch height; A, Enter or a tap call [onClick].
 */
@Composable
fun SyncStatusBadge(
    badge: SyncBadge,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    val (ground, accent, icon) =
        when (badge) {
            SyncBadge.Synced -> Triple(colors.successSoft, colors.success, GsIcons.success)
            SyncBadge.Syncing -> Triple(colors.infoSoft, colors.info, GsIcons.sync)
            SyncBadge.Offline -> Triple(colors.warningSoft, colors.warning, GsIcons.cloudOff)
            is SyncBadge.Conflicts, SyncBadge.NeedsSetup -> Triple(colors.warningSoft, colors.warning, GsIcons.warning)
            SyncBadge.Failed -> Triple(colors.dangerSoft, colors.danger, GsIcons.error)
        }
    Row(
        modifier =
            modifier
                .focusRing(role = Role.Button, shape = GsShapes.small, focusedFill = null, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.background(ground, GsShapes.small).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Text(badge.word, style = MaterialTheme.typography.titleSmall, color = colors.ink)
        }
    }
}
