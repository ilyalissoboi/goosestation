// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The face symbols on the touch overlay's buttons: [PsFaceSymbols]' shapes without their plates, each scaled so its
 * drawn extent, stroke included, is [GLYPH] of the button's width on its larger side, centred on the button, at one
 * stroke weight ([STROKE] of the width) with [PsGlyph]'s square caps and mitred corners. So Cross, Circle, Triangle
 * and Square read as one size, which their glyphs on the Controllers page (on plates) don't.
 */
object TouchGlyphs {
    const val GLYPH = 0.4f
    const val STROKE = 0.055f

    // Compose's default, and Android's.
    private const val MITER_LIMIT = 4f

    /** Where [name]'s symbol is drawn on [button], stroke included. */
    fun bounds(
        name: String,
        button: Rect,
    ): Rect = place(name, button).bounds

    fun DrawScope.drawTouchGlyph(
        name: String,
        button: Rect,
        color: Color,
    ) {
        val placed = place(name, button)
        val stroke = Stroke(placed.stroke, miter = MITER_LIMIT, cap = StrokeCap.Square, join = StrokeJoin.Miter)
        for (part in PsFaceSymbols.parts(name)) {
            when (part) {
                is SymbolPart.Polygon ->
                    drawPath(
                        Path().apply {
                            part.points.map(placed::map).forEachIndexed { i, p ->
                                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                            }
                            close()
                        },
                        color,
                        style = stroke,
                    )
                is SymbolPart.Segment ->
                    drawLine(color, placed.map(part.from), placed.map(part.to), placed.stroke, StrokeCap.Square)
                is SymbolPart.Ring ->
                    drawCircle(color, part.radius * placed.scale, placed.map(part.centre), style = stroke)
            }
        }
    }

    private class Placement(
        val scale: Float,
        val offset: Offset,
        val stroke: Float,
        val bounds: Rect,
    ) {
        fun map(point: Offset) = Offset(offset.x + point.x * scale, offset.y + point.y * scale)
    }

    private fun place(
        name: String,
        button: Rect,
    ): Placement {
        val parts = PsFaceSymbols.parts(name)
        val stroke = STROKE * button.width
        val target = GLYPH * button.width
        // The stroke adds the same to each side at any scale, so a side grows linearly with the scale.
        val one = outline(parts, 1f, stroke)
        val two = outline(parts, 2f, stroke)
        val scale =
            min(
                1f + (target - one.width) / (two.width - one.width),
                1f + (target - one.height) / (two.height - one.height),
            )
        val drawn = outline(parts, scale, stroke)
        val offset = button.center - drawn.center
        return Placement(scale, offset, stroke, drawn.translate(offset))
    }

    // The bounds of the parts drawn at [scale] from the origin with a [stroke] wide line.
    private fun outline(
        parts: List<SymbolPart>,
        scale: Float,
        stroke: Float,
    ): Rect {
        val half = stroke / 2
        val points = mutableListOf<Offset>()
        for (part in parts) {
            when (part) {
                is SymbolPart.Polygon -> {
                    val corners = part.points.map { it * scale }
                    corners.forEachIndexed { i, v ->
                        points +=
                            corner(corners[(i - 1).mod(corners.size)], v, corners[(i + 1) % corners.size], half)
                    }
                }
                is SymbolPart.Segment -> {
                    val a = part.from * scale
                    val b = part.to * scale
                    val along = unit(b - a) * half
                    val across = Offset(-along.y, along.x)
                    for (end in listOf(a - along, b + along)) {
                        points += end + across
                        points += end - across
                    }
                }
                is SymbolPart.Ring -> {
                    val r = part.radius * scale + half
                    val c = part.centre * scale
                    points += c - Offset(r, r)
                    points += c + Offset(r, r)
                }
            }
        }
        return Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    // The outer points of a convex polygon's stroke at corner [v]: the mitre's tip, or both edges' ends past the limit.
    private fun corner(
        prev: Offset,
        v: Offset,
        next: Offset,
        half: Float,
    ): List<Offset> {
        val toPrev = unit(prev - v)
        val toNext = unit(next - v)
        val outward = unit(-(toPrev + toNext))
        // The mitre reaches half / sin(angle / 2); cos of the angle is the dot product.
        val cos = toPrev.x * toNext.x + toPrev.y * toNext.y
        val sinHalf = sqrt(max(0f, (1f - cos) / 2f))
        return if (1f / sinHalf <= MITER_LIMIT) {
            listOf(v + outward * (half / sinHalf))
        } else {
            listOf(v + normalAway(toPrev, outward) * half, v + normalAway(toNext, outward) * half)
        }
    }

    // The unit normal of an edge along [edge] on [outward]'s side.
    private fun normalAway(
        edge: Offset,
        outward: Offset,
    ): Offset {
        val normal = Offset(-edge.y, edge.x)
        return if (normal.x * outward.x + normal.y * outward.y >= 0f) normal else -normal
    }

    private fun unit(v: Offset): Offset = v / v.getDistance()
}
