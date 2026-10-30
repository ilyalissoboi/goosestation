// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutSettlingTest {
    private val settling = LayoutSettling()

    @Test
    fun unsettledUntilAPull() {
        assertFalse(settling.settled)
    }

    @Test
    fun aCompletePullThatDidNotGrowTheLayoutSettlesIt() {
        val pull = settling.pullStarting()
        settling.pulled(pull, complete = true)

        assertTrue(settling.settled)
    }

    // The folder couldn't be reached, or a file failed: the game settings that name more files may be among those left.
    @Test
    fun anIncompletePullDoesNotSettleIt() {
        val pull = settling.pullStarting()
        settling.pulled(pull, complete = false)

        assertFalse(settling.settled)
    }

    @Test
    fun aPullThatGrewTheLayoutDoesNotSettleIt() {
        val pull = settling.pullStarting()
        // Its own game settings, read once it's done, or a push's meanwhile.
        settling.unsettle()
        settling.pulled(pull, complete = true)

        assertFalse(settling.settled)
    }

    @Test
    fun growthUnsettlesItUntilTheNextCompletePull() {
        settling.pulled(settling.pullStarting(), complete = true)

        settling.unsettle()
        assertFalse(settling.settled)
        settling.pulled(settling.pullStarting(), complete = false)
        assertFalse(settling.settled)
        settling.pulled(settling.pullStarting(), complete = true)
        assertTrue(settling.settled)
    }

    @Test
    fun anIncompletePullLeavesASettledLayoutSettled() {
        settling.pulled(settling.pullStarting(), complete = true)

        settling.pulled(settling.pullStarting(), complete = false)

        assertTrue(settling.settled)
    }
}
