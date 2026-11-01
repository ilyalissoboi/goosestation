// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.DiscChoice
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.core.GameDisc
import io.github.ilyalissoboi.goosestation.ui.components.KeyHint
import io.github.ilyalissoboi.goosestation.ui.components.KeyHints
import io.github.ilyalissoboi.goosestation.ui.components.LabelValue
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/**
 * The Change disc panel of the in-game menu, beside it as the slot panel is: one row per disc of the [choice], "Disc 1"
 * to "Disc N", each with its own title (or its serial, when its title is the game's, [gameTitle]) under it, and the
 * disc in the drive marked Current on `beak-soft`. A row's A, Enter or tap puts that disc in
 * ([GameMenuActions.pickDisc]). As it opens, the current disc takes the focus, which can't leave the panel: B and Back
 * close it ([GameMenu] handles them).
 */
@Composable
fun DiscPanel(
    choice: DiscChoice,
    gameTitle: String,
    actions: GameMenuActions,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    val rows = remember { HashMap<Int, FocusRequester>() }

    fun row(number: Int) = rows.getOrPut(number) { FocusRequester() }

    LaunchedEffect(Unit) {
        val target = choice.discs.firstOrNull { it.number == choice.current } ?: choice.discs.firstOrNull()
        target?.let { row(it.number).requestFocus() }
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
        Text("Change disc", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
        choice.error?.let { error ->
            Notice(NoticeStatus.Danger) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
            }
        }
        if (choice.busy) {
            Text("Inserting…", style = MaterialTheme.typography.bodyLarge, color = colors.inkMuted)
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            choice.discs.forEach { disc ->
                DiscRow(
                    disc,
                    detail = if (disc.title.isNotBlank() && disc.title != gameTitle) disc.title else disc.serial,
                    current = disc.number == choice.current,
                    onClick = { actions.pickDisc(disc.number) },
                    modifier = Modifier.focusRequester(row(disc.number)),
                )
            }
        }
        KeyHints(listOf(KeyHint(PadGlyphs.confirm, "Insert"), KeyHint(PadGlyphs.back, "Back")))
    }
}

/** A disc: "Disc N" and its [detail] under it, with Current at the end when it's in the drive. */
@Composable
private fun DiscRow(
    disc: GameDisc,
    detail: String,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (current) colors.beakSoft else colors.surface, GsShapes.medium)
                .focusRing(role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val name =
            @Composable {
                Text("Disc ${disc.number}", style = MaterialTheme.typography.titleMedium, color = colors.ink)
            }
        // LabelValue takes a label and a value, so only the current disc's row has one.
        if (current) {
            LabelValue(
                label = name,
                value = { Text("Current", style = MaterialTheme.typography.bodyLarge, color = colors.inkMuted) },
            )
        } else {
            name()
        }
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
        }
    }
}
