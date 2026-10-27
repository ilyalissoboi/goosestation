// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCardPathsTest {
    @Test
    fun aFolderHoldsEveryFileUnderIt() {
        val paths = listOf("cards/")

        assertTrue(MemoryCardPaths.matches("cards/shared_card_1.mcd", paths))
        assertTrue(MemoryCardPaths.matches("cards/sub/SLUS-00001_1.mcd", paths))
        assertFalse(MemoryCardPaths.matches("memcards/shared_card_1.mcd", paths))
        assertFalse(MemoryCardPaths.matches("cards2/a.mcd", paths))
    }

    @Test
    fun aFileMatchesOnlyItself() {
        val paths = listOf("memcards/", "shared/one.mcd")

        assertTrue(MemoryCardPaths.matches("shared/one.mcd", paths))
        assertFalse(MemoryCardPaths.matches("shared/one.mcd.bak", paths))
        assertFalse(MemoryCardPaths.matches("shared/two.mcd", paths))
    }

    // The data folder itself as the memory card folder: every file may be a card.
    @Test
    fun theDataRootHoldsEverything() {
        assertTrue(MemoryCardPaths.matches("settings.ini", listOf("")))
    }

    // A running game's per-game card (gamesettings/<serial>.ini) is held back, not only the memory card folder.
    @Test
    fun aRunningGamesCardFileIsDeferred() {
        val paths = listOf("memcards/", "shared/one.mcd")

        assertTrue(MemoryCardPaths.deferred("shared/one.mcd", paths, gameActive = true))
        assertTrue(MemoryCardPaths.deferred("memcards/a.mcd", paths, gameActive = true))
        assertFalse(MemoryCardPaths.deferred("shared/other.bin", paths, gameActive = true))
        assertFalse(MemoryCardPaths.deferred("shared/one.mcd", paths, gameActive = false))
    }

    // Once the game stops the core no longer reports its card, which stays a card for the rest of the run: the pull
    // after the game brings in what it held back, and its last writes go out.
    @Test
    fun cardFilesReportedEarlierAreKept() {
        val seen = listOf("memcards/", "shared/one.mcd")

        assertEquals(
            listOf("cards/", "shared/one.mcd"),
            MemoryCardPaths.merge(reported = listOf("cards/"), previous = seen),
        )
        assertEquals(
            listOf("memcards/", "shared/one.mcd", "two.mcd"),
            MemoryCardPaths.merge(reported = listOf("memcards/", "shared/one.mcd", "two.mcd"), previous = seen),
        )
        // Folders aren't kept: settings.ini names the memory card folder.
        assertEquals(listOf("memcards/"), MemoryCardPaths.merge(listOf("memcards/"), previous = listOf("cards/")))
    }

    @Test
    fun theDefaultIsTheMemcardsFolder() {
        assertTrue(MemoryCardPaths.matches("memcards/a.mcd", MemoryCardPaths.DEFAULT))
        assertFalse(MemoryCardPaths.matches("savestates/a.sav", MemoryCardPaths.DEFAULT))
    }
}
