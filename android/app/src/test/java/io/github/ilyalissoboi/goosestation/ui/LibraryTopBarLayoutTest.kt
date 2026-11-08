// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the library's search field goes, by the room the bar has. */
class LibraryTopBarLayoutTest {
    // The goose and "Library" at text 1.0×, as the Thor lays them out, and the five 48 dp buttons 8 dp apart.
    private val title = 121.dp
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
        assertEquals(BarLayout.SearchBelow, barLayout(width = 576.dp, title = 143.dp, trailing = buttons))
    }

    @Test
    fun theFieldNeedsItsLeastWidth() {
        // Title, gap, 160 dp of field, gap, buttons: 569 dp.
        assertEquals(BarLayout.OneRow, barLayout(width = 569.dp, title = title, trailing = buttons))
        assertEquals(BarLayout.SearchBelow, barLayout(width = 568.dp, title = title, trailing = buttons))
    }
}
