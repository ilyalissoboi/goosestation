// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A game's start ([GameStartGate]): it boots once the settings work asked for before it has landed. */
class GameStartGateTest {
    private val settled = CompletableDeferred<Unit>()
    private var begins = 0
    private var boots = 0

    private fun TestScope.gate() = GameStartGate(backgroundScope) { settled.await() }

    @Test
    fun aStartBootsAfterTheSettle() =
        runTest {
            val gate = gate()
            gate.start(begin = { begins++ == 0 }, boot = { boots++ })
            runCurrent()
            assertEquals(0, boots)
            assertTrue(gate.starting)

            settled.complete(Unit)
            runCurrent()

            assertEquals(1, boots)
            assertFalse(gate.starting)
        }

    @Test
    fun aSecondStartWhileStartingIsIgnored() =
        runTest {
            val gate = gate()
            gate.start(begin = { true }, boot = { boots++ })
            gate.start(begin = { true }, boot = { boots += 10 })

            settled.complete(Unit)
            runCurrent()

            assertEquals(1, boots)
        }

    // Review Focus 5: a start the checks refuse after the wait leaves nothing starting, so the next one goes.
    @Test
    fun aRefusedStartLeavesNothingStarting() =
        runTest {
            val gate = gate()
            settled.complete(Unit)
            gate.start(begin = { false }, boot = { boots++ })
            runCurrent()

            assertEquals(0, boots)
            assertFalse(gate.starting)

            gate.start(begin = { true }, boot = { boots++ })
            runCurrent()
            assertEquals(1, boots)
        }
}
