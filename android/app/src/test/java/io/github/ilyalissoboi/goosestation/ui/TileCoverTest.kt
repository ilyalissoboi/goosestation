// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** How big a tile's cover is: its column's width, unless a whole tile wouldn't fit the grid's height. */
class TileCoverTest {
    // The title's two lines, the details line and the gaps under the cover, at text 1.0×.
    private val text = 70.dp

    @Test
    fun aTallGridKeepsTheColumnsWidth() {
        assertEquals(178.dp, coverSize(cellWidth = 178.dp, rowHeight = 600.dp, textHeight = text))
    }

    @Test
    fun noLimitKeepsTheColumnsWidth() {
        assertEquals(178.dp, coverSize(cellWidth = 178.dp, rowHeight = Dp.Infinity, textHeight = text))
    }

    @Test
    fun aShortGridShrinksTheCoverSoTheWholeTileFits() {
        // The Thor at text 1.0×: 236 dp for a whole tile.
        assertEquals(166.dp, coverSize(cellWidth = 178.dp, rowHeight = 236.dp, textHeight = text))
    }

    @Test
    fun theCoverNeverGetsSmallerThanItsLeast() {
        assertEquals(96.dp, coverSize(cellWidth = 178.dp, rowHeight = 120.dp, textHeight = text))
    }
}
