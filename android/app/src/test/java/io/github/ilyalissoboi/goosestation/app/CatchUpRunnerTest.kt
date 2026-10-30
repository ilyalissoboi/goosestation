// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchUpRunnerTest {
    private val logged = mutableListOf<String>()
    private val runner = CatchUpRunner(logged::add)
    private var pulls = 0

    /** Asks for a catch-up as a pull or push that grew the layout does; true if it's this caller's to run. */
    private fun grow(): Boolean {
        runner.layoutGrew()
        return runner.request()
    }

    @Test
    fun growthRefusesStartsUntilItsCatchUpIsDone() {
        assertTrue(runner.startsAllowed)

        runner.layoutGrew()

        // Before the caller has even asked for the catch-up: a library scan may run in between.
        assertFalse(runner.startsAllowed)
    }

    @Test
    fun startsAreAllowedAfterANormalEnd() =
        runTest {
            assertTrue(grow())
            runner.run {
                assertFalse(runner.startsAllowed)
                ++pulls < 2
            }

            assertEquals(2, pulls)
            assertTrue(runner.startsAllowed)
        }

    // Its last round grew the layout again, and nothing pulls it: games mustn't be refused for ever. A round's own
    // growth isn't flagged: the run refuses starts while it lasts anyway.
    @Test
    fun startsAreAllowedAfterARunThatHitTheCap() =
        runTest {
            assertTrue(grow())
            runner.run {
                pulls++
                true
            }

            assertEquals(CatchUp.MAX_PULLS, pulls)
            assertEquals(1, logged.size)
            assertTrue(runner.startsAllowed)
        }

    @Test
    fun aRoundQueuedWhileItRunsKeepsStartsRefused() =
        runTest {
            assertTrue(grow())
            val allowed = mutableListOf<Boolean>()
            runner.run {
                pulls++
                // A push grows the layout during the first round: the runner takes it, so that caller runs nothing.
                if (pulls == 1) assertFalse(grow())
                allowed += runner.startsAllowed
                false
            }

            assertEquals(2, pulls)
            assertEquals(listOf(false, false), allowed)
            assertTrue(runner.startsAllowed)
        }

    @Test
    fun startsAreAllowedAfterAFailedRun() =
        runTest {
            assertTrue(grow())
            runCatching { runner.run { error("the core went away") } }

            assertTrue(runner.startsAllowed)
            // And the next growth is its own caller's to run again.
            assertTrue(grow())
        }

    // A push grows the layout during a round that pulled nothing: its own catch-up, asked for once its library scan is
    // done, pulls what it named.
    @Test
    fun aGrowthFlaggedDuringARoundStillRefusesStartsAfterIt() =
        runTest {
            assertTrue(grow())
            runner.run {
                runner.layoutGrew()
                false
            }

            assertFalse(runner.startsAllowed)
            assertTrue(runner.request())
            runner.run {
                assertFalse(runner.startsAllowed)
                false
            }
            assertTrue(runner.startsAllowed)
        }

    @Test
    fun aGuardedRunThatGrewTheLayoutAsksForItsCatchUp() {
        var requested = 0

        val result =
            runner.guardGrowth({ requested++ }) { onGrowth ->
                onGrowth()
                "pushed"
            }

        assertEquals("pushed", result)
        assertEquals(1, requested)
        assertFalse(runner.startsAllowed)
    }

    @Test
    fun aGuardedRunThatDidNotGrowTheLayoutAsksForNothing() {
        var requested = 0

        runner.guardGrowth({ requested++ }) { }

        assertEquals(0, requested)
        assertTrue(runner.startsAllowed)
    }

    // The push ticker is cancelled as the app goes to the background, maybe right after the push grew the layout.
    @Test
    fun aGuardedRunCancelledAfterGrowingTheLayoutStillAsksForItsCatchUp() =
        runTest {
            var requested = 0
            val push =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    runner.guardGrowth({ requested++ }) { onGrowth ->
                        onGrowth()
                        awaitCancellation()
                    }
                }

            push.cancel()
            push.join()

            assertEquals(1, requested)
            assertFalse(runner.startsAllowed)
        }
}
