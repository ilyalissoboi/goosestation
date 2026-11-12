// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.BuildConfig
import io.github.ilyalissoboi.goosestation.app.CheckNowState
import io.github.ilyalissoboi.goosestation.settings.InterfaceSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.LabelValue
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.update.AppVersion
import io.github.ilyalissoboi.goosestation.update.CheckResult

/**
 * The Interface page: pausing on start or when a controller disconnects, the resume state on exit, save state backups,
 * and updates (the check at start, the channel, the [version] and Check now, with [updates]; only the version
 * without), from [values]. Each change comes as the whole [values] with that setting changed ([onChange]). The first
 * row is focused on opening; B or Back go back ([onBack]).
 */
@Composable
fun InterfaceScreen(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    onBack: () -> Unit,
    updates: InterfaceUpdates? = null,
    version: String = BuildConfig.VERSION_NAME,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    SettingsPage("Interface", onBack = onBack) {
        GameSwitch(
            values,
            onChange,
            "Pause on start",
            InterfaceSettings.START_PAUSED,
            "Each game starts paused, in its menu.",
            Modifier.focusRequester(first),
        )
        GameSwitch(
            values,
            onChange,
            "Pause when a controller disconnects",
            InterfaceSettings.PAUSE_ON_DISCONNECT,
            "Opens the game's menu.",
        )
        GameSwitch(
            values,
            onChange,
            "Save state on exit",
            InterfaceSettings.SAVE_STATE_ON_EXIT,
            "Exit game saves where you are, to resume from next time.",
        )
        GameSwitch(
            values,
            onChange,
            "Create save state backups",
            InterfaceSettings.SAVE_STATE_BACKUPS,
            "Saving over a slot keeps the old state as a .bak file.",
        )
        UpdateRows(values, onChange, updates, version)
    }
}

/** Check now on the Interface page: what it shows ([check]) and what starts it ([onCheckNow]). */
data class InterfaceUpdates(
    val check: CheckNowState,
    val onCheckNow: () -> Unit,
)

/** What Check now says it found. */
fun checkNowLine(result: CheckResult): String =
    when (result) {
        CheckResult.UpToDate -> "GooseStation is up to date."
        is CheckResult.Newer -> {
            val version = AppVersion.short(result.release.versionName)
            "GooseStation $version is available. Update from the library."
        }
        is CheckResult.Older -> "This channel's build is older than yours."
        CheckResult.NoRelease -> "This channel has no release yet."
        CheckResult.NoAndroidBuild -> "This channel's release has no Android build yet."
        CheckResult.Unreachable -> "GitHub couldn't be reached. Check your network connection."
        is CheckResult.Unreadable ->
            result.status?.let { "GitHub's answer couldn't be read (HTTP $it)." } ?: "GitHub's answer couldn't be read."
    }

// The Updates section: the check at start, the channel, the version and Check now, its result brought into view as it
// comes; without [updates] (a build that can't update), only the version, saying so.
@Composable
private fun UpdateRows(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    updates: InterfaceUpdates?,
    version: String,
) {
    val colors = GooseStationTheme.colors
    SettingsSection("Updates")
    if (updates != null) {
        GameSwitch(
            values,
            onChange,
            "Check for updates at start",
            InterfaceSettings.CHECK_UPDATES_AT_START,
            "Looks for a newer GooseStation each time the app starts.",
        )
        GameChoices(
            values,
            onChange,
            "Update channel",
            InterfaceSettings.UPDATE_CHANNEL,
            listOf(
                Choice("preview", "Preview", note = "Built from master."),
                Choice("latest", "Rolling", note = "Built from dev."),
            ),
        )
    }
    LabelValue(
        label = { Text("Version", style = MaterialTheme.typography.bodyLarge, color = colors.ink) },
        value = { Text(breakable(version), style = GooseStationTheme.type.mono, color = colors.inkMuted) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
    if (updates == null) {
        Text(
            "This build doesn't check for updates.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    val line =
        when (val check = updates.check) {
            CheckNowState.Idle -> null
            CheckNowState.Checking -> "Checking for updates…"
            is CheckNowState.Done -> checkNowLine(check.result)
        }
    // As it changes, not as the page opens on the first row.
    val bring = remember { BringIntoViewRequester() }
    var lineShown by remember { mutableStateOf(line) }
    LaunchedEffect(line) {
        if (line != null && line != lineShown) bring.bringIntoView()
        lineShown = line
    }
    Column(
        Modifier.bringIntoViewRequester(bring).padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Not disabled while checking, which would take the focus from it: a press then does nothing.
        ActionButton("Check now", onClick = { if (updates.check != CheckNowState.Checking) updates.onCheckNow() })
        line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted) }
    }
}
