// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a scrolling list puts what gains focus: in the middle, on a whole pixel. A half-pixel target can't be scrolled
 * to, and a page with two requests pending never settled (ScrollingTest.aQuickDownThenAOpensAListAndTheScrollSettles).
 */
class CentredScrollingTest {
    @Test
    fun anEvenGapCentresExactly() {
        // 100 px tall in 300: 100 above it, 100 below.
        assertEquals(400f - 100f, CentredScrolling.calculateScrollDistance(400f, 100f, 300f), 0f)
    }

    @Test
    fun anOddGapRoundsToAWholePixel() {
        // 101 px tall in 300: 199 px of gap, 99 above it rather than 99.5.
        assertEquals(400f - 99f, CentredScrolling.calculateScrollDistance(400f, 101f, 300f), 0f)
        assertEquals(-99f, CentredScrolling.calculateScrollDistance(0f, 101f, 300f), 0f)
    }

    @Test
    fun somethingAsTallAsTheListShowsItsStart() {
        assertEquals(400f, CentredScrolling.calculateScrollDistance(400f, 300f, 300f), 0f)
        assertEquals(400f, CentredScrolling.calculateScrollDistance(400f, 500f, 300f), 0f)
    }
}
