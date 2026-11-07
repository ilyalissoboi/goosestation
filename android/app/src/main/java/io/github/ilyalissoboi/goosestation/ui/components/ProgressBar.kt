// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/**
 * A 6 dp `beak` bar on `sunken` showing how far a job has gone: [fraction] (0 to 1) of it. While how much there is isn't
 * known (null), a third of a bar sits at [phase] (0 to 1) along it, which the caller moves as the job does (the bytes so
 * far, say): never on a clock, so a screen with one still settles. Never focusable: the job's step and its Cancel go
 * beside it. TalkBack reads it as a progress bar.
 */
@Composable
fun ProgressBar(
    fraction: Float?,
    modifier: Modifier = Modifier,
    phase: Float = 0f,
) {
    val colors = GooseStationTheme.colors
    val range =
        fraction?.let { ProgressBarRangeInfo(it.coerceIn(0f, 1f), 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(GsShapes.small)
            .background(colors.sunken)
            .semantics { progressBarRangeInfo = range },
    ) {
        if (fraction != null) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(colors.beak))
        } else {
            // From a third off the start to the end, so the sweep enters and leaves the bar.
            val at = phase.coerceIn(0f, 1f) * (4f / 3f) - 1f / 3f
            Box(
                Modifier
                    .offset(x = maxWidth * at)
                    .width(maxWidth / 3)
                    .fillMaxHeight()
                    .background(colors.beak),
            )
        }
    }
}
