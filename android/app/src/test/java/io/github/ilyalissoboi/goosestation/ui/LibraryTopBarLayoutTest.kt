// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the library's search field goes, by the room the bar has. */
class LibraryTopBarLayoutTest {
    // The goose and "Library" at text 1.0×, as the Thor lays them out (measured), and the five 48 dp buttons 8 dp apart.
    private val title = 141.dp
    private val buttons = 272.dp

    @Test
    fun theThorAtTextOneHasOneRow() {
        assertEquals(BarLayout.OneRow, barLayout(width = 576.dp, title = title, trailing = buttons))
    }

    @Test
    fun aSyncBadgeMovesTheSearchUnderTheBar() {
        assertEquals(BarLayout.SearchBelow, barLayout(width = 576.dp, title = title, trailing = buttons + 108.dp))
    }

    @Test
    fun aPhoneInPortraitStacksTheBar() {
        assertEquals(BarLayout.Stacked, barLayout(width = 296.dp, title = title, trailing = buttons))
    }

    @Test
    fun theThorAtTextOnePointThreeMovesTheSearchUnderTheBar() {
        // The title hardly grows; the field's least width does, with the text it holds.
        assertEquals(
            BarLayout.SearchBelow,
            barLayout(width = 576.dp, title = 143.dp, trailing = buttons, fontScale = 1.3f),
        )
        // A wider handheld keeps one row at 1.3×.
        assertEquals(BarLayout.OneRow, barLayout(width = 736.dp, title = 143.dp, trailing = buttons, fontScale = 1.3f))
    }

    @Test
    fun theFieldNeedsItsLeastWidth() {
        // Title, gap, 144 dp of field, gap, buttons: 573 dp.
        assertEquals(BarLayout.OneRow, barLayout(width = 573.dp, title = title, trailing = buttons))
        assertEquals(BarLayout.SearchBelow, barLayout(width = 572.dp, title = title, trailing = buttons))
    }
}
