// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.SaveSlots
import io.github.ilyalissoboi.goosestation.app.SlotEntry
import io.github.ilyalissoboi.goosestation.app.SlotMode
import io.github.ilyalissoboi.goosestation.app.SlotPanel
import io.github.ilyalissoboi.goosestation.ui.components.KeyHint
import io.github.ilyalissoboi.goosestation.ui.components.KeyHints
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes
import java.time.ZonedDateTime

/**
 * The slot panel of the in-game menu: the [panel]'s slots, each with its screenshot (once read), its name, Saved or
 * Empty, and when it was saved, as seen from [now]. A slot row's A, Enter or tap picks it ([GameMenuActions.pickSlot]).
 * In Load, an empty slot can't be focused. As it opens, the panel's focus slot takes the focus; a question to replace a
 * slot shows in place of the list, with Cancel focused, and the slot is focused again after it. The focus can't leave
 * the panel: B and Back close it ([GameMenu] handles them).
 */
@Composable
fun SaveSlotPanel(
    panel: SlotPanel,
    actions: GameMenuActions,
    now: ZonedDateTime,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    val saving = panel.mode == SlotMode.Save
    val entries = panel.entries
    val title = remember { FocusRequester() }
    val rows = remember { HashMap<Int, FocusRequester>() }

    fun row(slot: Int) = rows.getOrPut(slot) { FocusRequester() }

    fun pickable(entry: SlotEntry) = saving || entry.savedAt != null

    // The slot to focus once the question about replacing it is answered.
    var returnTo by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(entries == null, panel.replacing) {
        val replacing = panel.replacing
        when {
            // The question focuses its Cancel.
            replacing != null -> returnTo = replacing
            entries == null -> title.requestFocus()
            else -> {
                val wanted = returnTo ?: panel.focus
                returnTo = null
                val target =
                    entries.firstOrNull { it.slot == wanted && pickable(it) } ?: entries.firstOrNull(::pickable)
                (target?.let { row(it.slot) } ?: title).requestFocus()
            }
        }
    }

    Column(
        modifier =
            modifier
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .background(colors.surface, GsShapes.large)
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Focusable while nothing else in the panel can take the focus, so it stays in the panel and B reaches it.
        val nothingToFocus = panel.replacing == null && (entries == null || entries.none(::pickable))
        Text(
            if (saving) "Save state" else "Load state",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
            modifier = Modifier.focusRequester(title).focusable(enabled = nothingToFocus),
        )
        panel.error?.let { error ->
            Notice(NoticeStatus.Danger) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
            }
        }
        if (panel.busy) Line(if (saving) "Saving…" else "Loading…")
        if (entries != null && !saving && entries.none { it.savedAt != null }) Line("No saves yet for this game.")
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val replacing = panel.replacing
            if (replacing != null) {
                Question(
                    "Replace the save in slot $replacing?",
                    "Replace",
                    onConfirm = actions::confirmReplace,
                    onCancel = actions::cancelReplace,
                )
            } else {
                entries?.forEach { entry ->
                    SlotRow(
                        entry,
                        now,
                        enabled = pickable(entry),
                        onClick = { actions.pickSlot(entry.slot) },
                        modifier = Modifier.focusRequester(row(entry.slot)),
                    )
                }
            }
        }
        KeyHints(
            listOf(KeyHint(PadGlyphs.confirm, if (saving) "Save" else "Load"), KeyHint(PadGlyphs.back, "Back")),
        )
    }
}

/**
 * A slot: its screenshot (or the memory card icon), its name, Saved or Empty, and when it was saved. The picture is
 * 112 × 84 dp, or 64 × 48 dp in a row under 320 dp wide, so the words beside it fit at a large text size.
 */
@Composable
private fun SlotRow(
    entry: SlotEntry,
    now: ZonedDateTime,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    val savedAt = entry.savedAt
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(8.dp),
    ) {
        val compact = maxWidth < 320.dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(if (compact) 64.dp else 112.dp, if (compact) 48.dp else 84.dp)
                        .clip(GsShapes.small)
                        .background(colors.sunken),
                contentAlignment = Alignment.Center,
            ) {
                val picture = entry.picture
                if (picture != null) {
                    val bitmap = remember(picture) { picture.toImageBitmap() }
                    Image(
                        bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        GsIcons.memoryCard,
                        contentDescription = null,
                        tint = colors.inkFaint,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (entry.slot == SaveSlots.RESUME) "Resume" else "Slot ${entry.slot}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) colors.ink else colors.inkFaint,
                )
                Text(
                    if (savedAt != null) "Saved" else "Empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                savedAt?.let {
                    Text(SaveTimes.label(it, now), style = GooseStationTheme.type.mono, color = colors.inkMuted)
                }
            }
        }
    }
}

@Composable
private fun Line(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = GooseStationTheme.colors.inkMuted,
        modifier = Modifier.fillMaxWidth(),
    )
}
