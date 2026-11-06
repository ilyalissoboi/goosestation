// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The game whose own settings the pages show: its [title], whether it's [running] (its changes apply straight away,
 * and some only at its next start), and whether hardcore mode is active in it now ([hardcoreActive]).
 */
data class GameContext(
    val title: String,
    val running: Boolean,
    val hardcoreActive: Boolean,
)

/** The game a settings page is for; null on the global pages. */
val LocalGameContext = staticCompositionLocalOf<GameContext?> { null }
