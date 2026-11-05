// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.ilyalissoboi.goosestation.settings.InterfaceSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage

/**
 * The Interface page: pausing on start or when a controller disconnects, the resume state on exit, and save state
 * backups, from [values]. Each change comes as the whole [values] with that setting changed ([onChange]). The first
 * row is focused on opening; B or Back go back ([onBack]).
 */
@Composable
fun InterfaceScreen(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    SettingsPage("Interface", onBack = onBack) {
        Switch(
            values,
            onChange,
            "Pause on start",
            InterfaceSettings.START_PAUSED,
            "Each game starts paused, in its menu.",
            Modifier.focusRequester(first),
        )
        Switch(
            values,
            onChange,
            "Pause when a controller disconnects",
            InterfaceSettings.PAUSE_ON_DISCONNECT,
            "Opens the game's menu.",
        )
        Switch(
            values,
            onChange,
            "Save state on exit",
            InterfaceSettings.SAVE_STATE_ON_EXIT,
            "Exit game saves where you are, to resume from next time.",
        )
        Switch(
            values,
            onChange,
            "Create save state backups",
            InterfaceSettings.SAVE_STATE_BACKUPS,
            "Saving over a slot keeps the old state as a .bak file.",
        )
    }
}
