// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** The game the in-game menu leaves for, started once the last one has stopped and its memory cards are synced. */
class GameSwitchTest {
    private val started = mutableListOf<StateStart>()
    private val crash = StateStart("content://games/crash.cue", StateRef("", 3, shared = true))

    // A sync that throws fails its coroutine only, as the app's scope does.
    private fun TestScope.switch() =
        GameSwitch(
            CoroutineScope(
                StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, _ -> },
            ),
        ) { started += it }

    @Test
    fun theGameLeftForStartsAfterTheSync() =
        runTest {
            val switch = switch()
            val order = mutableListOf<String>()
            switch.leaving(crash)

            switch.stopped { order += "sync" }
            runCurrent()

            assertEquals(listOf("sync"), order)
            assertEquals(listOf(crash), started)
        }

    @Test
    fun aStopWithoutASwitchStartsNothing() =
        runTest {
            val switch = switch()

            switch.stopped {}
            runCurrent()

            assertEquals(emptyList<StateStart>(), started)
        }

    // Review: a sync that fails drops the switch, rather than leaving it for some later, unrelated stop.
    @Test
    fun aFailedSyncDropsTheSwitch() =
        runTest {
            val switch = switch()
            switch.leaving(crash)
            switch.stopped { throw IOException("The data folder can't be reached.") }
            runCurrent()

            switch.stopped {}
            runCurrent()

            assertEquals(emptyList<StateStart>(), started)
        }
}
