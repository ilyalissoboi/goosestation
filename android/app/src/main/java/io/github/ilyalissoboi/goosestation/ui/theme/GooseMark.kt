// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale

/** One face of the mark: its colour and its corners, on the mark's 64-unit grid. */
private class Facet(
    val color: Color,
    vararg val points: Float,
)

// The design system's goose-mark.svg, face by face, back to front: the head, neck and beak of the blocky goose.
private val facets =
    listOf(
        Facet(Color(0xFFC9C5BC), 30f, 30f, 36f, 25f, 36f, 72f, 30f, 72f),
        Facet(Color(0xFFF7F5F0), 18f, 30f, 30f, 30f, 18f, 72f),
        Facet(Color(0xFFE4E1D9), 30f, 30f, 30f, 72f, 18f, 72f),
        Facet(Color(0xFFFFFFFF), 14f, 14f, 20f, 9f, 40f, 9f, 34f, 14f),
        Facet(Color(0xFFD6D2CA), 34f, 14f, 40f, 9f, 40f, 25f, 34f, 30f),
        Facet(Color(0xFFFFFFFF), 14f, 14f, 34f, 14f, 14f, 30f),
        Facet(Color(0xFFECE9E2), 34f, 14f, 34f, 30f, 14f, 30f),
        Facet(Color(0xFFFF9447), 34f, 20f, 40f, 15f, 58f, 15f, 52f, 20f),
        Facet(Color(0xFFFF7A1A), 34f, 20f, 52f, 20f, 52f, 28f, 34f, 28f),
        Facet(Color(0xFFD95300), 52f, 20f, 58f, 15f, 58f, 23f, 52f, 28f),
        Facet(Color(0xFF14171C), 26f, 17f, 30f, 17f, 30f, 21f, 26f, 21f),
    )

private val paths =
    facets.map { facet ->
        Path().apply {
            moveTo(facet.points[0], facet.points[1])
            for (i in 2 until facet.points.size step 2) lineTo(facet.points[i], facet.points[i + 1])
            close()
        }
    }

/** The goose mark, in full colour, for dark grounds; square, filling [modifier]'s size. */
@Composable
fun GooseMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 64f
        clipRect {
            scale(unit, unit, pivot = Offset.Zero) {
                facets.forEachIndexed { index, facet -> drawPath(paths[index], facet.color) }
            }
        }
    }
}
