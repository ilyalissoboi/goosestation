// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberScaleTest {
    private val sectors = NumberScale.Range(0, 32, 1, presets = listOf(0, 8, 32)) { "$it sectors" }
    private val osd = NumberScale.Range(25, 500, 5, presets = listOf(100)) { "$it%" }
    private val speeds = NumberScale.Steps(listOf(0, 10, 100, 125), presets = listOf(100)) { "$it%" }

    @Test
    fun aRangeStepsAndStopsAtItsEnds() {
        assertNull(sectors.next(0, -1))
        assertEquals(32, sectors.next(31, 1))
        assertNull(sectors.next(32, 1))
        assertEquals(7, sectors.next(8, -1))
    }

    @Test
    fun aRangeStepsByItsStep() {
        assertEquals(105, osd.next(100, 1))
        assertEquals(95, osd.next(100, -1))
    }

    @Test
    fun offTheRangeGoesToTheNearestThatWay() {
        assertEquals(32, sectors.next(40, -1))
        assertNull(sectors.next(40, 1))
        assertEquals(0, sectors.next(-3, 1))
        assertEquals(105, osd.next(102, 1))
        assertEquals(100, osd.next(102, -1))
    }

    @Test
    fun stepsWalkTheirValues() {
        assertEquals(125, speeds.next(100, 1))
        assertNull(speeds.next(125, 1))
        assertNull(speeds.next(0, -1))
        assertEquals(100, speeds.next(15, 1))
        assertEquals(10, speeds.next(15, -1))
        assertEquals(125, speeds.next(110, 1))
    }

    @Test
    fun eachLabelsItsValues() {
        assertEquals("8 sectors", sectors.label(8))
        assertEquals("133%", speeds.label(133))
    }
}
