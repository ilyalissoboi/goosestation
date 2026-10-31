// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.ResumeOffer
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes
import java.time.ZonedDateTime

/**
 * "Resume where you left off?", over the library before a game with a resume state starts: the [offer]'s game, the
 * picture and time of its resume state, as seen from [now], then **Resume** ([onResume], focused) and **Start over**
 * ([onStartOver]). B and Back cancel ([onCancel]), on the key-up of a press that began here. It takes every tap, and
 * the focus can't leave it for the library under it.
 */
@Composable
fun ResumePrompt(
    offer: ResumeOffer,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onCancel: () -> Unit,
    now: ZonedDateTime = ZonedDateTime.now(),
) {
    BackHandler(onBack = onCancel)
    val colors = GooseStationTheme.colors
    val resume = remember { FocusRequester() }
    LaunchedEffect(Unit) { resume.requestFocus() }
    // The keys whose key-down the prompt has seen, and whose key-up hasn't come yet.
    val down = remember { HashSet<Key>() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .pointerInput(Unit) { detectTapGestures {} }
                .onKeyEvent { event ->
                    val key = event.key
                    if (key != Key.Back && key != Key.ButtonB) return@onKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> down += key
                        KeyEventType.KeyUp -> if (down.remove(key)) onCancel()
                    }
                    true
                }.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup()
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .background(colors.surface, GsShapes.large)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Resume where you left off?", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
                Text(
                    offer.game.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier.size(224.dp, 168.dp).clip(GsShapes.small).background(colors.sunken),
                contentAlignment = Alignment.Center,
            ) {
                val picture = offer.picture
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
            Text(SaveTimes.label(offer.savedAt, now), style = GooseStationTheme.type.mono, color = colors.inkMuted)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
