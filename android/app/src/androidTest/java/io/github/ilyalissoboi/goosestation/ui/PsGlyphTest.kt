// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.PsGlyph
import io.github.ilyalissoboi.goosestation.ui.components.PsGlyphs
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** The PlayStation button glyphs: one for each of Port 1's inputs, 32 dp tall, named for screen readers. */
@RunWith(AndroidJUnit4::class)
class PsGlyphTest {
    @get:Rule val rule = createComposeRule()

    private val colors = GsColors()

    private fun show(
        vararg names: String,
        fontScale: Float = 1f,
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GooseStationTheme {
                    Column {
                        for (name in names) PsGlyph(name, "$name glyph", Modifier.testTag(name))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun everyNameHasAGlyph() {
        assertEquals(24, PsGlyphs.names.size)
        show(*PsGlyphs.names.toTypedArray())

        for (name in PsGlyphs.names) {
            val bounds = rule.onNodeWithTag(name).getUnclippedBoundsInRoot()
            assertTrue("$name has no width", bounds.width > 0.dp)
            assertEquals("$name's height", 32f, bounds.height.value, 0.5f)
        }
        assertFalse(PsGlyphs.has("Analog"))
        assertFalse(PsGlyphs.has("Turbo"))
    }

    @Test
    fun theGlyphGrowsWithTheFontScale() {
        show("Triangle", fontScale = 1.3f)

        assertEquals(
            41.6f,
            rule
                .onNodeWithTag("Triangle")
                .getUnclippedBoundsInRoot()
                .height.value,
            0.5f,
        )
    }

    @Test
    fun screenReadersHearTheName() {
        show("LLeft")

        val node = rule.onNodeWithContentDescription("LLeft glyph").fetchSemanticsNode()
        assertTrue("the glyph shows text to screen readers", node.config.getOrNull(SemanticsProperties.Text) == null)
    }

    @Test
    fun theFaceSymbolsAreInTheirColours() {
        show("Triangle", "Circle", "Cross", "Square")

        fun has(
            name: String,
            colour: Color,
        ): Boolean {
            val pixels = rule.onNodeWithTag(name).captureToImage().toPixelMap()
            for (x in 0 until pixels.width) {
                for (y in 0 until pixels.height) {
                    val p = pixels[x, y]
                    if (abs(p.red - colour.red) < 0.01f && abs(p.green - colour.green) < 0.01f &&
                        abs(p.blue - colour.blue) < 0.01f
                    ) {
                        return true
                    }
                }
            }
            return false
        }
        assertTrue(has("Triangle", colors.psTriangle))
        assertTrue(has("Circle", colors.psCircle))
        assertTrue(has("Cross", colors.psCross))
        assertTrue(has("Square", colors.psSquare))
        assertFalse(has("Cross", colors.psTriangle))
    }

    @Test
    fun theLabelsAreText() {
        show("L1", "R2", "L3", "Select", "Start")

        for (label in listOf("L1", "R2", "L3", "SELECT", "START")) {
            rule.onNode(hasText(label), useUnmergedTree = true).assertExists()
        }
    }
}
