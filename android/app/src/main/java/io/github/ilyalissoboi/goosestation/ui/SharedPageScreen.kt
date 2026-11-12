// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import io.github.ilyalissoboi.goosestation.app.PageExtras
import io.github.ilyalissoboi.goosestation.app.SharedPage
import io.github.ilyalissoboi.goosestation.settings.SettingValues

/**
 * [page] with its [values], the global settings' or a game's, and what its screen shows beside them from [extras].
 * Memory cards isn't one: each owner's page has its own layout, and draws it itself.
 */
@Composable
fun SharedPageScreen(
    page: SharedPage,
    values: SettingValues,
    extras: PageExtras,
    onChange: (SettingValues) -> Unit,
    onBack: () -> Unit,
) {
    when (page) {
        SharedPage.Console -> ConsoleScreen(values, extras.names, onChange, onBack)
        SharedPage.Emulation -> EmulationScreen(values, extras.rewindUsage, onChange, onBack)
        SharedPage.Graphics -> GraphicsScreen(values, extras.names, extras.vulkanFailed, onChange, onBack)
        SharedPage.Audio -> AudioScreen(values, extras.names, onChange, onBack)
        SharedPage.Advanced -> AdvancedScreen(values, extras.names, extras.fastmemAvailable, onChange, onBack)
        SharedPage.MemoryCards -> error("Memory cards is each owner's own page")
    }
}
