// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.OsdMessage
import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes
import kotlinx.coroutines.delay

/**
 * The messages over the running game, from the app and the core: [messages] stacked at the top left, oldest first, in
 * the `osd` style, each fading out as it goes (or just going, with animations off). While [fastForward], its marker
 * shows at the top right. At [nextExpiry] (an `uptimeMillis` time) it calls [onExpire]. Nothing in it takes input.
 */
@Composable
fun OsdLayer(
    messages: List<OsdMessage>,
    fastForward: Boolean,
    nextExpiry: Long?,
    onExpire: () -> Unit,
) {
    val colors = GooseStationTheme.colors
    LaunchedEffect(nextExpiry) {
        val at = nextExpiry ?: return@LaunchedEffect
        delay((at - SystemClock.uptimeMillis()).coerceAtLeast(0))
        onExpire()
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
