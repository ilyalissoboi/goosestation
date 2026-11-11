// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/**
 * A panel beside the in-game menu (Load and Save state, Change disc, Patch codes, Achievements): a `surface` panel at
 * `radius-lg`, 24 dp inside, its [content] 16 dp apart. The D-pad can't leave it; the menu gives the focus back to the
 * panel's row as it closes. Its tabs' L1 and R1, if any, go on the [modifier] ([tabKeys]).
 */
@Composable
fun SidePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .focusProperties {
                    onExit = { if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() }
                }.focusGroup()
                .background(GooseStationTheme.colors.surface, GsShapes.large)
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}
