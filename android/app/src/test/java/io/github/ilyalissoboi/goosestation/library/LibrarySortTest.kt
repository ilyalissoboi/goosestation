// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.testGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LibrarySortTest {
    private fun titles(
        games: List<GameEntry>,
        key: SortKey,
        reverse: Boolean = false,
    ) = LibraryOrder.sorted(games, LibrarySort(key, reverse)).map { it.title }

    @Test
    fun titleAscendsByDefault() {
        val games = listOf(testGame("C"), testGame("A"), testGame("B"))

        assertEquals(LibrarySort(SortKey.Title, false), LibrarySort())
        assertEquals(listOf("A", "B", "C"), titles(games, SortKey.Title))
        assertEquals(listOf("C", "B", "A"), titles(games, SortKey.Title, reverse = true))
    }

    @Test
    fun anUnknownValueSortsAsTitle() {
        assertEquals(SortKey.Title, SortKey.of(12))
        assertEquals(SortKey.Title, SortKey.of(-1))
        assertEquals(SortKey.entries.toList(), (0..7).map { SortKey.of(it) })
    }

    @Test
    fun theOfferedSortsAreBigPicturesLessTwo() {
        assertEquals(
            listOf(
                SortKey.Title,
                SortKey.LastPlayed,
                SortKey.TimePlayed,
                SortKey.Serial,
                SortKey.FileTitle,
                SortKey.FileSize,
            ),
            SortKey.OFFERED,
        )
    }

    @Test
    fun lastPlayedIsMostRecentFirst() {
        val games = listOf(testGame("Old", last = 100), testGame("New", last = 300), testGame("Mid", last = 200))

        assertEquals(listOf("New", "Mid", "Old"), titles(games, SortKey.LastPlayed))
        assertEquals(listOf("Old", "Mid", "New"), titles(games, SortKey.LastPlayed, reverse = true))
    }

    @Test
    fun timePlayedAndSizesAscend() {
        val games =
            listOf(
                testGame("Two", played = 2, size = 2, uncompressed = 2),
                testGame("Three", played = 3, size = 3, uncompressed = 3),
                testGame("One", played = 1, size = 1, uncompressed = 1),
            )

        for (key in listOf(SortKey.TimePlayed, SortKey.FileSize, SortKey.UncompressedSize)) {
            assertEquals(key.name, listOf("One", "Two", "Three"), titles(games, key))
            assertEquals(key.name, listOf("Three", "Two", "One"), titles(games, key, reverse = true))
        }
    }

    @Test
    fun serialComparesBytes() {
        val games = listOf(testGame("Lower", serial = "slus-0"), testGame("Upper", serial = "SLUS-1"))

        assertEquals(listOf("Upper", "Lower"), titles(games, SortKey.Serial))
    }

    @Test
    fun typeSortsDiscsFirst() {
        val games =
            listOf(
                testGame("Psf", type = 4),
                testGame("Exe", type = 2),
                testGame("Disc", type = 0),
                testGame("M3u", type = 3),
            )

        assertEquals(listOf("Disc", "Exe", "M3u", "Psf"), titles(games, SortKey.Type))
        assertEquals(listOf("Psf", "M3u", "Exe", "Disc"), titles(games, SortKey.Type, reverse = true))
    }

    @Test
    fun tiesFallBackToTitleReversedWithTheRest() {
        val games = listOf(testGame("B", played = 5), testGame("C", played = 1), testGame("A", played = 5))

        assertEquals(listOf("C", "A", "B"), titles(games, SortKey.TimePlayed))
        assertEquals(listOf("B", "A", "C"), titles(games, SortKey.TimePlayed, reverse = true))
    }

    @Test
    fun fileTitleComparesOnlyUpToTheShorterName() {
        val games =
            listOf(
                testGame("Zebra", path = "/games/Crash.cue"),
                testGame("Apple", path = "/games/Crash 2.cue"),
                testGame("Mango", path = "/games/Apex.cue"),
            )

        // "Crash" and "Crash 2" tie, so their titles decide.
        assertEquals(listOf("Mango", "Apple", "Zebra"), titles(games, SortKey.FileTitle))
        assertEquals(listOf("Zebra", "Apple", "Mango"), titles(games, SortKey.FileTitle, reverse = true))
    }

    @Test
    fun fileTitlesThatStartOneAnotherNeverBreakTheSort() {
        // "Crash" ties with both "Crash 2" and "Crasha" (each starts with it), which don't tie with each other: no
        // order of three such can satisfy every comparison, and a sort that checks its comparator throws.
        val names = listOf("Crash", "Crash 2", "Crasha", "Cras", "Crash Team", "Crash!", "Crashb", "C", "Cr")
        for (seed in 1..300) {
            val random = Random(seed)
            val games =
                (1..random.nextInt(32, 600)).map {
                    testGame(
                        title = "T${random.nextInt(1000)}",
                        path = "/games/${names[random.nextInt(names.size)]}.cue",
                    )
                }
            for (reverse in listOf(false, true)) {
                val sorted = LibraryOrder.sorted(games, LibrarySort(SortKey.FileTitle, reverse))
                assertEquals(games.size, sorted.size)
            }
        }
    }

    @Test
    fun caseFoldsOnlyAscii() {
        assertEquals(listOf("apple", "Banana"), titles(listOf(testGame("Banana"), testGame("apple")), SortKey.Title))
        // strcasecmp folds ASCII only: É (C3 89) comes before é (C3 A9) whatever follows.
        assertEquals(listOf("Ébc", "éab"), titles(listOf(testGame("éab"), testGame("Ébc")), SortKey.Title))
        // A shorter title that's the start of a longer one comes first.
        assertEquals(listOf("Crash", "crash 2"), titles(listOf(testGame("crash 2"), testGame("Crash")), SortKey.Title))
    }

    @Test
    fun wordsForEachSort() {
        fun words(
            key: SortKey,
            reverse: Boolean,
        ) = LibrarySort(key, reverse).let {
            listOf(LibraryWords.sortName(key), LibraryWords.direction(it), LibraryWords.shortDirection(it))
        }

        assertEquals(listOf("Title", "A to Z", "A–Z"), words(SortKey.Title, false))
        assertEquals(listOf("Title", "Z to A", "Z–A"), words(SortKey.Title, true))
        assertEquals(listOf("Serial", "A to Z", "A–Z"), words(SortKey.Serial, false))
        assertEquals(listOf("File title", "Z to A", "Z–A"), words(SortKey.FileTitle, true))
        assertEquals(
            listOf("Last played", "Most recent first", "most recent first"),
            words(SortKey.LastPlayed, false),
        )
        assertEquals(
            listOf("Last played", "Least recent first", "least recent first"),
            words(SortKey.LastPlayed, true),
        )
        assertEquals(
            listOf("Time played", "Least played first", "least played first"),
            words(SortKey.TimePlayed, false),
        )
        assertEquals(listOf("Time played", "Most played first", "most played first"), words(SortKey.TimePlayed, true))
        assertEquals(listOf("File size", "Smallest first", "smallest first"), words(SortKey.FileSize, false))
        assertEquals(
            listOf("Uncompressed size", "Largest first", "largest first"),
            words(SortKey.UncompressedSize, true),
        )
        assertEquals(listOf("Type", "Discs first", "discs first"), words(SortKey.Type, false))
        assertEquals(listOf("Type", "PSFs first", "PSFs first"), words(SortKey.Type, true))
    }

    @Test
    fun aThousandGamesSortInAFrame() {
        val random = Random(16)
        val games =
            (1..1000).map { n ->
                testGame(
                    title = "Game ${random.nextInt(10_000)}",
                    serial = "SLUS-%05d".format(random.nextInt(100_000)),
                    path = "content://p/tree/t/document/t%2FGame%20$n.cue",
                    played = random.nextLong(100_000),
                    last = random.nextLong(100_000),
                    size = random.nextLong(1L shl 30),
                    uncompressed = random.nextLong(1L shl 30),
                    type = listOf(0, 2, 3, 4)[random.nextInt(4)],
                )
            }
        val sorts = SortKey.entries.flatMap { key -> listOf(false, true).map { LibrarySort(key, it) } }
        repeat(20) { sorts.forEach { LibraryOrder.sorted(games, it) } }

        for (sort in sorts) {
            val times =
                (1..15).map {
                    val start = System.nanoTime()
                    LibraryOrder.sorted(games, sort)
                    System.nanoTime() - start
                }
            val median = times.sorted()[times.size / 2] / 1_000_000.0
            assertTrue("$sort took $median ms", median < 8.0)
        }
    }
}
