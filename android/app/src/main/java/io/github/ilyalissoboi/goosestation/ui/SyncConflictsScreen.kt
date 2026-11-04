// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.SyncConflictWords
import io.github.ilyalissoboi.goosestation.core.SyncConflict
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.ChoiceList
import io.github.ilyalissoboi.goosestation.ui.components.ChoiceListHost
import io.github.ilyalissoboi.goosestation.ui.components.LocalChoiceListHost
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import java.time.ZoneId

/**
 * The saves memory card sync can't settle alone: one row per conflict, its name and its versions (times in [zone]).
 * A, Enter or a tap on a row open its choices under it, as a cycler's list: this device's version, each other
 * device's, or deciding later, the newest focused and marked. A picks ([onResolve], with the choice's index); B or
 * Back close the list. "Keep all from this device" asks first, Cancel focused, then calls [onKeepAll]. The first row
 * has focus on opening; with none left, the screen goes back ([onBack]), as B and Back do.
 */
@Composable
fun SyncConflictsScreen(
    conflicts: List<SyncConflict>,
    zone: ZoneId,
    onResolve: (id: Long, choice: Int) -> Unit,
    onKeepAll: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = GooseStationTheme.colors
    var asking by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val keepAll = remember { FocusRequester() }
    LaunchedEffect(conflicts.isEmpty()) { if (conflicts.isEmpty()) onBack() }
    LaunchedEffect(Unit) { if (conflicts.isNotEmpty()) first.requestFocus() }

    SettingsPage("Sync conflicts", onBack = onBack) {
        Text(
            SyncConflictWords.intro(conflicts.size),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        conflicts.forEachIndexed { i, conflict ->
            ConflictRow(
                conflict,
                zone,
                onPick = { onResolve(conflict.id, it) },
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
        if (asking) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Question(
                    "Keep this device's version of every save above? The other versions stay in the sync history.",
                    "Keep all",
                    onConfirm = {
                        asking = false
                        onKeepAll()
                    },
                    onCancel = { asking = false },
                )
            }
            BackHandler { asking = false }
        } else if (conflicts.isNotEmpty()) {
            ActionButton(
                "Keep all from this device",
                onClick = { asking = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(keepAll),
            )
        }
    }
    // Back on the button the question replaced.
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(asking) {
        if (asked && !asking && conflicts.isNotEmpty()) keepAll.requestFocus()
        asked = asking
    }
}

/** A conflict: its name and its versions; A opens its choices under it, and A on one picks it ([onPick]). */
@Composable
private fun ConflictRow(
    conflict: SyncConflict,
    zone: ZoneId,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    val row = remember { FocusRequester() }
    val host = LocalChoiceListHost.current ?: remember { ChoiceListHost() }
    val key = remember { Any() }
    val open = host.open === key

    fun close() {
        host.close()
        row.requestFocus()
    }

    BackHandler(enabled = open, onBack = ::close)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRequester(row)
                .onGloballyPositioned { if (open) host.openCoordinates = it }
                .focusRing(role = Role.Button, onClick = { if (open) close() else host.open(key, row) })
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(conflict.name, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        Text(
            SyncConflictWords.summary(conflict, zone),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
        if (open) {
            val labels = SyncConflictWords.choices(conflict)
            ChoiceList(
                labels.mapIndexed { i, label -> Choice(i, label) },
                conflict.defaultChoice,
                onPick = {
                    host.close()
                    onPick(it.value)
                },
            )
        }
    }
}
