// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.ilyalissoboi.goosestation.ui.components.OverlayHost
import io.github.ilyalissoboi.goosestation.ui.components.OverlayHostBox

/**
 * [content] in an [OverlayHost], as the app shows its screens: their overlays draw into the host, above them, and a
 * closed one goes a frame later, so a screen's test sees the focus come back as it does in the app.
 */
@Composable
fun InOverlayHost(content: @Composable () -> Unit) {
    val host = remember { OverlayHost() }
    OverlayHostBox(host, content)
}
