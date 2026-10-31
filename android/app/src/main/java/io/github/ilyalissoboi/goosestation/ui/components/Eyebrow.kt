// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import java.util.Locale

/**
 * A small label over what it names, such as "Paused" over the game's title: [text], written in sentence case, shown in
 * capitals in the eyebrow style.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text.uppercase(Locale.ROOT),
        style = GooseStationTheme.type.eyebrow,
        color = GooseStationTheme.colors.inkMuted,
        modifier = modifier,
    )
}
