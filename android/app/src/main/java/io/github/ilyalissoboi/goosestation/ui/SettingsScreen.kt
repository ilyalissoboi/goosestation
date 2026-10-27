// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.components.NavRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage

/** The settings hub's entries, each a page, in the order the hub lists them; all but Folders need the core. */
enum class SettingsEntry(
    val label: String,
    val summary: String,
    val needsCore: Boolean = true,
) {
    Controllers("Controllers", "Remap each controller's buttons"),
    Graphics("Graphics", "Renderer, resolution, aspect ratio, FPS"),
    Bios("BIOS", "Which BIOS each region uses, fast boot"),
    Folders("Folders", "BIOS, games and data folders", needsCore = false),
}

/** Said, in place of its summary, on an entry that needs the core while it isn't [SettingsScreen]'s `coreReady`. */
const val NEEDS_CORE = "Available once GooseStation has started."

/**
 * The settings hub: an entry per page, which A, Enter or a tap open ([onOpen]). Until [coreReady] (the core has
 * started, and no game is under way), only Folders can be opened: the others are disabled, and say why. On opening,
 * [focusEntry] has focus, the page just left, or else Controllers, or Folders if it can't be. B or Back go back to the
 * library ([onBack]).
 */
@Composable
fun SettingsScreen(
    focusEntry: SettingsEntry?,
    coreReady: Boolean,
    onOpen: (SettingsEntry) -> Unit,
    onBack: () -> Unit,
) {
    fun enabled(entry: SettingsEntry) = coreReady || !entry.needsCore

    val requesters = remember { SettingsEntry.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) {
        val first = listOfNotNull(focusEntry, SettingsEntry.Controllers).firstOrNull(::enabled) ?: SettingsEntry.Folders
        requesters.getValue(first).requestFocus()
    }

    SettingsPage("Settings", onBack = onBack) {
        SettingsEntry.entries.forEach { entry ->
            NavRow(
                entry.label,
                if (enabled(entry)) entry.summary else NEEDS_CORE,
                onClick = { onOpen(entry) },
                modifier = Modifier.focusRequester(requesters.getValue(entry)),
                enabled = enabled(entry),
            )
        }
    }
}

/** A page still to come, with only its [title] and a line saying so. B or Back go back ([onBack]). */
@Composable
fun ComingSoonScreen(
    title: String,
    onBack: () -> Unit,
) {
    SettingsPage(title, onBack = onBack) {
        Text(
            "Coming soon.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
