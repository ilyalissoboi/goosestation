// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.AchievementNotice
import io.github.ilyalissoboi.goosestation.app.AchievementOverlay
import io.github.ilyalissoboi.goosestation.app.CardKind
import io.github.ilyalissoboi.goosestation.app.OsdMessage
import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The messages over the running game, from the app and the core: [messages] stacked at the top left, oldest first, in
 * the `osd` style, each fading out as it goes (or just going, with animations off). While [fastForward], its marker
 * shows at the top right. At [nextExpiry] (an `uptimeMillis` time) it calls [onExpire]. RetroAchievements' unlock
 * [card] shows at the top centre, and its live [indicators] at the bottom right, their pictures from [badge]; at
 * [overlayExpiry] it calls [onOverlayExpire]. Nothing in it takes input.
 */
@Composable
fun OsdLayer(
    messages: List<OsdMessage>,
    fastForward: Boolean,
    nextExpiry: Long?,
    onExpire: () -> Unit,
    card: AchievementNotice.Card? = null,
    indicators: List<AchievementOverlay.Indicator> = emptyList(),
    overlayExpiry: Long? = null,
    onOverlayExpire: () -> Unit = {},
    badge: (String) -> ImageBitmap? = { null },
) {
    val colors = GooseStationTheme.colors
    LaunchedEffect(nextExpiry) {
        val at = nextExpiry ?: return@LaunchedEffect
        delay((at - SystemClock.uptimeMillis()).coerceAtLeast(0))
        onExpire()
    }
    LaunchedEffect(overlayExpiry) {
        val at = overlayExpiry ?: return@LaunchedEffect
        delay((at - SystemClock.uptimeMillis()).coerceAtLeast(0))
        onOverlayExpire()
    }
    // Read once: a change to the system's setting takes effect from the next game.
    val fade = remember { ValueAnimator.areAnimatorsEnabled() }

    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        LazyColumn(
            modifier = Modifier.align(Alignment.TopStart),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false,
        ) {
            items(messages, key = { it.id }) { message ->
                OsdRow(
                    message,
                    Modifier.animateItem(
                        fadeInSpec = null,
                        placementSpec = null,
                        fadeOutSpec = if (fade) tween(160) else null,
                    ),
                )
            }
        }
        // Keyed on the card, so each one fades in and out on its own.
        AnimatedContent(
            targetState = card,
            modifier = Modifier.align(Alignment.TopCenter),
            transitionSpec = {
                if (fade) {
                    fadeIn(tween(160)) togetherWith fadeOut(tween(160))
                } else {
                    EnterTransition.None togetherWith
                        ExitTransition.None
                }
            },
            label = "card",
        ) { shown -> shown?.let { UnlockCard(it, badge(it.imagePath)) } }
        if (indicators.isNotEmpty()) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                indicators.forEach { IndicatorChip(it, badge(it.badgePath)) }
            }
        }
        if (fastForward) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .background(colors.osd, GsShapes.small)
                        .padding(8.dp),
            ) {
                Icon(
                    GsIcons.fastForward,
                    contentDescription = "Fast forward on",
                    tint = colors.osdInk,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun OsdRow(
    message: OsdMessage,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    Row(
        modifier = modifier.background(colors.osd, GsShapes.small).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.icon?.let { icon ->
            val bitmap = remember(icon) { icon.toImageBitmap() }
            Image(
                bitmap,
                contentDescription = null,
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(message.text, style = GooseStationTheme.type.osd, color = colors.osdInk)
    }
}

/** The core's picture as a bitmap to draw. */
internal fun ArgbImage.toImageBitmap() =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

/** An unlock or a mastery: the badge, what it is, its title and text, and an unlock's points, on `osd`. */
@Composable
private fun UnlockCard(
    card: AchievementNotice.Card,
    picture: ImageBitmap?,
) {
    val colors = GooseStationTheme.colors
    Row(
        modifier =
            Modifier
                .widthIn(max = 420.dp)
                .background(colors.osd, GsShapes.medium)
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).background(colors.sunken, GsShapes.small)) {
            picture?.let { Image(it, contentDescription = null, modifier = Modifier.size(48.dp)) }
        }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                (if (card.kind == CardKind.Mastery) "Set mastered" else "Achievement unlocked").uppercase(Locale.ROOT),
                style = GooseStationTheme.type.eyebrow,
                color = colors.beakInk,
            )
            Text(card.title, style = MaterialTheme.typography.titleMedium, color = colors.osdInk)
            if (card.text.isNotEmpty()) {
                Text(card.text, style = MaterialTheme.typography.bodyMedium, color = colors.osdInk)
            }
        }
        card.points?.let { Text("$it", style = GooseStationTheme.type.mono, color = colors.osdInk) }
    }
}

/** A live indicator: a leaderboard tracker's value, a challenge's badge, or the progress pop-up's badge and count. */
@Composable
private fun IndicatorChip(
    indicator: AchievementOverlay.Indicator,
    picture: ImageBitmap?,
) {
    val colors = GooseStationTheme.colors
    Row(
        modifier = Modifier.background(colors.osd, GsShapes.small).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (indicator.kind != AchievementOverlay.TRACKER) {
            Box(Modifier.size(32.dp).background(colors.sunken, GsShapes.small)) {
                picture?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
            }
        }
        if (indicator.text.isNotEmpty()) {
            Text(indicator.text, style = GooseStationTheme.type.mono, color = colors.osdInk)
        }
    }
}
