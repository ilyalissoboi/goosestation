// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CatchUpTest {
    private val logged = mutableListOf<String>()

    /** A pull that grows the layout the first [growths] times it runs, counting its runs in [pulls]. */
    private var pulls = 0

    private fun growing(growths: Int): suspend () -> Boolean = { ++pulls <= growths }

    @Test
    fun pullsUntilThePullThatDoesNotGrowTheLayout() =
        runTest {
            // settings.ini names a game settings folder, whose settings name a card: then nothing more.
            assertEquals(3, CatchUp.run(logged::add, growing(2)))
            assertEquals(3, pulls)
            assertEquals(emptyList<String>(), logged)
        }

    @Test
    fun aLayoutThatKeepsGrowingStopsAtTheCap() =
        runTest {
            assertEquals(CatchUp.MAX_PULLS, CatchUp.run(logged::add, growing(Int.MAX_VALUE)))
            assertEquals(4, pulls)
            assertEquals(listOf("The data layout kept growing; stopping after 4 pulls."), logged)
        }

    @Test
    fun aPullThatDoesNotGrowTheLayoutIsTheOnlyOne() =
        runTest {
            assertEquals(1, CatchUp.run(logged::add, growing(0)))
            assertEquals(1, pulls)
            assertEquals(emptyList<String>(), logged)
        }
}
