// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.github.ilyalissoboi.goosestation.ui.components.PsFaceSymbols
import io.github.ilyalissoboi.goosestation.ui.components.PsGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.TouchGlyphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The touch overlay's face glyphs: the PlayStation symbols without plates, one drawn size, centred in their buttons. */
class TouchGlyphsTest {
    private val names = listOf("Triangle", "Circle", "Cross", "Square")

    private fun button(size: Float) = Rect(Offset(100f, 40f), Size(size, size))

    @Test
    fun theSymbolsAreThePsGlyphShapes() {
        assertEquals(names, PsFaceSymbols.names)
        names.forEach { assertTrue(it, PsGlyphs.has(it)) }
    }

    @Test
    fun everyGlyphIsTheSameSize() {
        for (size in listOf(44f, 50f)) {
            val sides = names.associateWith { TouchGlyphs.bounds(it, button(size)).let { b -> max(b.width, b.height) } }
            val first = sides.getValue("Triangle")
            sides.forEach { (name, side) -> assertEquals("$name at $size", first, side, 0.5f) }
            val triangle = TouchGlyphs.bounds("Triangle", button(size))
            assertTrue(
                "Triangle's smaller side",
                min(triangle.width, triangle.height) >= 0.85f * max(triangle.width, triangle.height),
            )
        }
    }

    @Test
    fun everyGlyphIsCentredInItsButton() {
        for (size in listOf(44f, 50f)) {
            val button = button(size)
            for (name in names) {
                val centre = TouchGlyphs.bounds(name, button).center
                assertEquals("$name x at $size", button.center.x, centre.x, 0.5f)
                assertEquals("$name y at $size", button.center.y, centre.y, 0.5f)
            }
        }
    }

    @Test
    fun everyGlyphIsInsideItsButton() {
        val button = button(50f)
        val margin = 0.15f * button.width
        // The button's octagon: its edges 0.5 of the width from the centre, across and diagonally.
        val inner = 0.5f * button.width - margin
        for (name in names) {
            val bounds = TouchGlyphs.bounds(name, button)
            for (corner in listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight)) {
                val dx = abs(corner.x - button.center.x)
                val dy = abs(corner.y - button.center.y)
                assertTrue("$name across", max(dx, dy) <= inner)
                assertTrue("$name diagonally", (dx + dy) / sqrt(2f) <= inner)
            }
        }
    }
}
