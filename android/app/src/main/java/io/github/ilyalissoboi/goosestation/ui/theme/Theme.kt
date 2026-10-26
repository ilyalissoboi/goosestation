// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Always dark: the app is played on a TV or a handheld, often in a dim room.
private val colors =
    darkColorScheme(
        primary = Color(0xFFF2B33D),
        onPrimary = Color(0xFF2B1D00),
        secondary = Color(0xFFB8C4D6),
        background = Color(0xFF111316),
        onBackground = Color(0xFFE3E4E8),
        surface = Color(0xFF111316),
        onSurface = Color(0xFFE3E4E8),
        surfaceVariant = Color(0xFF2A2E35),
        onSurfaceVariant = Color(0xFFC4C7CE),
        error = Color(0xFFFFB4AB),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )

@Composable
fun GooseStationTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
