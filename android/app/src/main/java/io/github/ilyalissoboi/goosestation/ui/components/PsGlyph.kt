// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsColors

/** The core's Port 1 input names that have a [PsGlyph]: the DualShock's, but Analog, which is a hotkey here. */
object PsGlyphs {
    val names: Set<String> get() = GLYPHS.keys

    fun has(name: String): Boolean = name in GLYPHS
}

/**
 * [name]'s PlayStation button glyph, as drawn in `docs/design-system/pad-glyphs/`: 24 units tall, shown 32 dp tall
 * and growing with the font scale. Screen readers hear [contentDescription] only. Nothing for a name without one
 * ([PsGlyphs.has]).
 */
@Composable
fun PsGlyph(
    name: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val glyph = GLYPHS[name] ?: return
    val colors = GooseStationTheme.colors
    val unit = GLYPH_HEIGHT * LocalDensity.current.fontScale / UNITS
    Box(
        modifier
            // Not a merge boundary: in a row, the row says the input's name with the rest of it.
            .semantics { this.contentDescription = contentDescription }
            .size(unit * glyph.width, unit * UNITS),
    ) {
        Canvas(Modifier.size(unit * glyph.width, unit * UNITS)) {
            val scale = size.height / UNITS
            glyph.marks.forEach { draw(it, scale, colors) }
        }
        glyph.label?.let { GlyphLabel(it, unit) }
    }
}

/** A glyph's word, centred on its x and sitting on its baseline, in the text family, bold. */
@Composable
private fun GlyphLabel(
    label: Label,
    unit: Dp,
) {
    val density = LocalDensity.current
    // [unit] has the font scale in it already; toSp() takes it out, so the text doesn't scale twice.
    val size = with(density) { (unit * label.size).toSp() }
    Text(
        label.text,
        style =
            MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = size,
                lineHeight = size,
            ),
        color = GooseStationTheme.colors.ink,
        maxLines = 1,
        softWrap = false,
        modifier =
            Modifier
                // Its own node, out of the glyph's merged text: screen readers hear the content description only.
                .semantics(mergeDescendants = true) { hideFromAccessibility() }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val x = (unit * label.x).roundToPx() - placeable.width / 2
                    val y = (unit * label.baseline).roundToPx() - placeable[FirstBaseline]
                    layout(placeable.width, placeable.height) { placeable.place(x, y) }
                },
    )
}

private fun DrawScope.draw(
    mark: Mark,
    scale: Float,
    colors: GsColors,
) {
    when (mark) {
        is Mark.Shape -> {
            val path =
                Path().apply {
                    mark.points.chunked(2).forEachIndexed { i, (x, y) ->
                        if (i == 0) moveTo(x * scale, y * scale) else lineTo(x * scale, y * scale)
                    }
                    if (mark.closed) close()
                }
            mark.fill?.let { drawPath(path, it.color(colors), style = Fill) }
            mark.stroke?.let { drawPath(path, it.color(colors), style = stroke(mark.width, scale)) }
        }
        is Mark.Ring ->
            drawCircle(
                mark.stroke.color(colors),
                radius = mark.radius * scale,
                center = Offset(mark.x * scale, mark.y * scale),
                style = stroke(mark.width, scale),
            )
    }
}

private fun stroke(
    width: Float,
    scale: Float,
) = Stroke(width * scale, cap = StrokeCap.Square, join = StrokeJoin.Miter)

/** The colours a glyph uses, as tokens. */
private enum class Ink {
    Sunken,
    LineStrong,
    Ink,
    Triangle,
    Circle,
    Cross,
    Square,
    ;

    fun color(colors: GsColors): Color =
        when (this) {
            Sunken -> colors.sunken
            LineStrong -> colors.lineStrong
            Ink -> colors.ink
            Triangle -> colors.psTriangle
            Circle -> colors.psCircle
            Cross -> colors.psCross
            Square -> colors.psSquare
        }
}

/** Part of a glyph, in units of its 24-unit-tall box. */
private sealed interface Mark {
    /** A polygon (or, not [closed], a polyline) through [points], x then y. */
    data class Shape(
        val points: List<Float>,
        val fill: Ink? = null,
        val stroke: Ink? = null,
        val width: Float = 2f,
        val closed: Boolean = true,
    ) : Mark

    data class Ring(
        val x: Float,
        val y: Float,
        val radius: Float,
        val stroke: Ink,
        val width: Float = 2f,
    ) : Mark
}

/** A glyph's word: [text] centred on [x], on the [baseline], [size] units tall. */
private data class Label(
    val text: String,
    val x: Float,
    val baseline: Float,
    val size: Float,
)

private data class Glyph(
    val width: Float,
    val marks: List<Mark>,
    val label: Label? = null,
)

private val GLYPH_HEIGHT = 32.dp
private const val UNITS = 24f

private fun pts(vararg values: Number) = values.map { it.toFloat() }

private val OCTAGON = pts(8.5, 2, 15.5, 2, 22, 8.5, 22, 15.5, 15.5, 22, 8.5, 22, 2, 15.5, 2, 8.5)

private fun face(symbol: Mark) =
    Glyph(24f, listOf(Mark.Shape(OCTAGON, fill = Ink.Sunken, stroke = Ink.LineStrong, width = 1.5f), symbol))

private val PLUS =
    pts(
        8.5,
        2,
        15.5,
        2,
        15.5,
        8.5,
        22,
        8.5,
        22,
        15.5,
        15.5,
        15.5,
        15.5,
        22,
        8.5,
        22,
        8.5,
        15.5,
        2,
        15.5,
        2,
        8.5,
        8.5,
        8.5,
    )

private fun dpad(arm: List<Float>) =
    Glyph(24f, listOf(Mark.Shape(PLUS, stroke = Ink.Ink), Mark.Shape(arm, fill = Ink.Ink)))

private fun stick(
    side: String,
    head: List<Float>,
) = Glyph(
    24f,
    listOf(Mark.Shape(OCTAGON, stroke = Ink.Ink), Mark.Shape(head, fill = Ink.Ink)),
    Label(side, 12f, 15.5f, 9f),
)

private fun press(label: String) =
    Glyph(
        24f,
        listOf(
            Mark.Shape(OCTAGON, stroke = Ink.Ink),
            Mark.Shape(
                pts(9.5, 5, 14.5, 5, 19, 9.5, 19, 14.5, 14.5, 19, 9.5, 19, 5, 14.5, 5, 9.5),
                Ink.Sunken,
                Ink.Ink,
                1f,
            ),
        ),
        Label(label, 12f, 15f, 8f),
    )

// A shoulder button seen from behind: flat at the grip, angled at the top corners, more for 1 than for 2.
private fun shoulder(label: String): Glyph {
    val tab =
        if (label.endsWith(
                "1",
            )
        ) {
            pts(2, 21, 2, 8, 7, 3, 23, 3, 28, 8, 28, 21)
        } else {
            pts(2, 21, 2, 6, 5, 3, 25, 3, 28, 6, 28, 21)
        }
    return Glyph(30f, listOf(Mark.Shape(tab, Ink.Sunken, Ink.Ink)), Label(label, 15f, 16.5f, 9.5f))
}

private val UP = pts(12, 3.5, 15, 7, 9, 7)
private val DOWN = pts(12, 20.5, 15, 17, 9, 17)
private val LEFT = pts(3.5, 12, 7, 9, 7, 15)
private val RIGHT = pts(20.5, 12, 17, 9, 17, 15)

private val GLYPHS: Map<String, Glyph> =
    mapOf(
        "Up" to dpad(pts(8.5, 2, 15.5, 2, 15.5, 9, 12, 12, 8.5, 9)),
        "Down" to dpad(pts(8.5, 22, 15.5, 22, 15.5, 15, 12, 12, 8.5, 15)),
        "Left" to dpad(pts(2, 8.5, 2, 15.5, 9, 15.5, 12, 12, 9, 8.5)),
        "Right" to dpad(pts(22, 8.5, 22, 15.5, 15, 15.5, 12, 12, 15, 8.5)),
        "Triangle" to face(Mark.Shape(pts(12, 6.5, 17, 15.5, 7, 15.5), stroke = Ink.Triangle)),
        "Circle" to face(Mark.Ring(12f, 12f, 5f, Ink.Circle)),
        "Cross" to
            face(Mark.Shape(pts(8, 8, 16, 16, 12, 12, 16, 8, 8, 16), stroke = Ink.Cross, closed = false)),
        "Square" to face(Mark.Shape(pts(7.5, 7.5, 16.5, 7.5, 16.5, 16.5, 7.5, 16.5), stroke = Ink.Square)),
        "Select" to
            Glyph(
                60f,
                listOf(Mark.Shape(pts(2, 9, 16, 9, 16, 15, 2, 15), Ink.Sunken, Ink.Ink)),
                Label("SELECT", 38.6f, 15.5f, 8.5f),
            ),
        "Start" to
            Glyph(
                54f,
                listOf(Mark.Shape(pts(3, 6.5, 16, 12, 3, 17.5), Ink.Sunken, Ink.Ink)),
                Label("START", 35.5f, 15.5f, 8.5f),
            ),
        "L1" to shoulder("L1"),
        "R1" to shoulder("R1"),
        "L2" to shoulder("L2"),
        "R2" to shoulder("R2"),
        "L3" to press("L3"),
        "R3" to press("R3"),
        "LUp" to stick("L", UP),
        "LDown" to stick("L", DOWN),
        "LLeft" to stick("L", LEFT),
        "LRight" to stick("L", RIGHT),
        "RUp" to stick("R", UP),
        "RDown" to stick("R", DOWN),
        "RLeft" to stick("R", LEFT),
        "RRight" to stick("R", RIGHT),
    )
