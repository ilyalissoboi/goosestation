// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Memory card sync's state as the app reads it: once more for a refresh asked during a read, and never stuck. */
class MemcardSyncStatusTest {
    private val calls = mutableListOf<String>()
    private val api = FakeCoreApi(calls)

    private val logged = mutableListOf<String>()

    private fun TestScope.status() =
        MemcardSyncStatus(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            api,
            log = { message, _ -> logged += message },
        )

    private fun reads() = calls.count { it == "getMemcardSyncState" }

    // Sync says it changed while the state is read: what was read may be from before, so it's read again.
    @Test
    fun aRefreshAskedDuringAReadReadsOnceMore() =
        runTest {
            val status = status()
            api.whileReadingSyncState = { status.refresh() }

            status.refresh()
            runCurrent()

            assertEquals(2, reads())
            assertEquals(api.syncState, status.state)
        }

    // Refreshes asked before the read starts are all answered by it.
    @Test
    fun refreshesBeforeTheReadShareIt() =
        runTest {
            val status = status()

            status.refresh()
            status.refresh()
            runCurrent()

            assertEquals(1, reads())
        }

    // From review: a read that threw left the status reading for good, and every later refresh was dropped.
    @Test
    fun aReadThatFailsLeavesTheNextRefreshToRead() =
        runTest {
            val status = status()
            api.syncStateFails = IllegalStateException("No state")

            status.refresh()
            runCurrent()
            assertNull(status.state)
            assertEquals(listOf("Can't read memory card sync's state."), logged)

            api.syncStateFails = null
            status.refresh()
            runCurrent()

            assertEquals(2, reads())
            assertEquals(api.syncState, status.state)
        }
}
