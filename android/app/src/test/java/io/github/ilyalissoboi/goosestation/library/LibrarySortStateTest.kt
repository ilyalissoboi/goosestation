// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortStateTest {
    private val saved = mutableListOf<LibrarySort>()
    private val state = LibrarySortState { saved += it }
    private val lastPlayed = LibrarySort(SortKey.LastPlayed)
    private val stored = LibrarySort(SortKey.TimePlayed, reverse = true)

    @Test
    fun settingsIniGivesTheSort() {
        state.read(stored)
        state.coreStarted()

        assertEquals(stored, state.sort)
        assertEquals(emptyList<LibrarySort>(), saved)
    }

    @Test
    fun aPickOnceTheCoreRunsIsSaved() {
        state.read(stored)
        state.coreStarted()

        state.pick(lastPlayed)

        assertEquals(lastPlayed, state.sort)
        assertEquals(listOf(lastPlayed), saved)
    }

    @Test
    fun theSortAlreadyIsntSavedAgain() {
        state.coreStarted()

        state.pick(LibrarySort())

        assertEquals(emptyList<LibrarySort>(), saved)
    }

    @Test
    fun aPickBeforeTheCoreReadsSettingsIniIsKeptAndSavedOnceItRuns() {
        state.pick(lastPlayed)
        state.read(stored)
        assertEquals(emptyList<LibrarySort>(), saved)

        state.coreStarted()

        assertEquals(lastPlayed, state.sort)
        assertEquals(listOf(lastPlayed), saved)
    }

    @Test
    fun aPickBetweenTheReadAndTheStartIsSavedOnceItRuns() {
        state.read(stored)
        state.pick(lastPlayed)

        state.coreStarted()

        assertEquals(lastPlayed, state.sort)
        assertEquals(listOf(lastPlayed), saved)
    }

    @Test
    fun settingsIniPulledLaterGivesItsSort() {
        state.pick(lastPlayed)
        state.coreStarted()

        // Another device's settings.ini, pulled from the data folder and read again.
        state.read(stored)

        assertEquals(stored, state.sort)
        assertEquals(listOf(lastPlayed), saved)
    }
}
