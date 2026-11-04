// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** What memory card sync is told of the app's visibility, as the activity starts and stops around the core's start. */
class SyncVisibilityTest {
    private val told = mutableListOf<Boolean>()
    private val visibility = SyncVisibility { told += it }

    @Test
    fun backAndForthOnceTheCoreRuns() {
        visibility.coreStarted()
        told.clear()

        visibility.stopped()
        visibility.started()
        visibility.pullDone()

        assertEquals(listOf(true, false), told)
    }

    // The player left again while the pull on the way back ran: sync stays in the background.
    @Test
    fun leavingDuringThePullStaysInTheBackground() {
        visibility.coreStarted()
        visibility.stopped()
        visibility.started()
        told.clear()

        visibility.stopped()
        visibility.pullDone()

        assertEquals(emptyList<Boolean>(), told)
    }

    // The app went to the background while it was copying the data folder in, before the core started.
    @Test
    fun hiddenBeforeTheCoreStartsTellsItOnStart() {
        visibility.started()
        visibility.stopped()

        visibility.coreStarted()

        assertEquals(listOf(true), told)
    }

    @Test
    fun visibleWhenTheCoreStartsSaysNothing() {
        visibility.started()

        visibility.coreStarted()

        assertEquals(emptyList<Boolean>(), told)
    }

    @Test
    fun nothingBeforeTheCoreStarts() {
        visibility.started()
        visibility.stopped()
        visibility.started()
        visibility.pullDone()

        assertEquals(emptyList<Boolean>(), told)
    }
}
