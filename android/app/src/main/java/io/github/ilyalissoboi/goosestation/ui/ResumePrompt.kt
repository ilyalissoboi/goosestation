// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.ResumeOffer
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.ButtonRow
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import java.time.ZonedDateTime

/**
 * "Resume where you left off?", over the library before a game with a resume state starts: the [offer]'s game, the
 * picture and time of its resume state, as seen from [now], then **Resume** ([onResume], focused) and **Start over**
 * ([onStartOver]). When hardcore mode would start ([ResumeOffer.hardcore]), it says the game starts afresh, with only
 * **Start** ([onStartOver]). An [OverlayPanel]: B and Back cancel ([onCancel]), on the key-up of a press that began
 * here; it takes every tap, and the focus can't leave it for the library under it.
 */
@Composable
fun ResumePrompt(
    offer: ResumeOffer,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onCancel: () -> Unit,
    now: ZonedDateTime = ZonedDateTime.now(),
) {
    val colors = GooseStationTheme.colors
    val resume = remember { FocusRequester() }

    OverlayPanel(onBack = onCancel, title = offer.game.title) {
        LaunchedEffect(Unit) { resume.requestFocus() }
        if (offer.hardcore) {
            // A boot from the resume state would end hardcore mode: the game only starts afresh.
            Text(
                offer.game.title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(HARDCORE_RESUME, style = MaterialTheme.typography.bodyLarge, color = colors.inkMuted)
            ActionButton(
                "Start",
                onClick = onStartOver,
                kind = ButtonKind.Primary,
                icon = GsIcons.play,
                modifier = Modifier.focusRequester(resume),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Resume where you left off?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.ink,
                )
                Text(
                    offer.game.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SaveStatePicture(offer.picture, Modifier.size(224.dp, 168.dp))
            Text(SaveTimes.label(offer.savedAt, now), style = GooseStationTheme.type.mono, color = colors.inkMuted)
            ButtonRow {
                ActionButton(
                    "Resume",
                    onClick = onResume,
                    kind = ButtonKind.Primary,
                    icon = GsIcons.play,
                    modifier = Modifier.focusRequester(resume),
                )
                ActionButton("Start over", onClick = onStartOver)
            }
        }
    }
}

/** The resume prompt's words when hardcore mode would start, from the design. */
const val HARDCORE_RESUME =
    "Hardcore mode is on, so the game starts from the beginning. Your resume state is kept for later."
