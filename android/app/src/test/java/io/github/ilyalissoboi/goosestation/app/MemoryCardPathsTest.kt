// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

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

    @Test
    fun theDefaultIsTheMemcardsFolder() {
        assertTrue(MemoryCardPaths.matches("memcards/a.mcd", MemoryCardPaths.DEFAULT))
        assertFalse(MemoryCardPaths.matches("savestates/a.sav", MemoryCardPaths.DEFAULT))
    }
}
