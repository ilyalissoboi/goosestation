// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import io.github.ilyalissoboi.goosestation.core.GameDisc
import io.github.ilyalissoboi.goosestation.core.GameEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {
    private fun game(
        title: String,
        serial: String = "SLUS-00000",
        path: String = "content://p/tree/t/document/t%2F${title.replace(" ", "%20")}.cue",
        discs: List<GameDisc> = emptyList(),
    ) = GameEntry(path, title, serial, "NTSC-U", 0, 0, null, discs)

    private val crash =
        game("Crash Bandicoot", serial = "SCUS-94900", path = "content://p/tree/t/document/t%2Fcrash.cue")

    @Test
    fun matchesTitleFileNameOrSerialIgnoringCase() {
        assertTrue(LibrarySearch.matches(crash, "BAND"))
        assertTrue(LibrarySearch.matches(crash, "scus-949"))
        assertTrue(LibrarySearch.matches(crash, "crash.cue"))
        assertTrue(LibrarySearch.matches(crash, "ndic"))
        assertFalse(LibrarySearch.matches(crash, "spyro"))
        // Not the path's other parts.
        assertFalse(LibrarySearch.matches(crash, "tree"))
    }

    @Test
    fun matchesAnyDisc() {
        val game =
            game(
                "Final Fantasy VII",
                serial = "SCUS-94163",
                discs =
                    listOf(
                        GameDisc("content://p/tree/t/document/t%2Fff7-1.cue", "SCUS-94163", "Final Fantasy VII", 1),
                        GameDisc("content://p/tree/t/document/t%2Fff7-2.cue", "SCUS-94164", "Final Fantasy VII", 2),
                    ),
            )

        assertTrue(LibrarySearch.matches(game, "94164"))
        assertTrue(LibrarySearch.matches(game, "ff7-2"))
    }

    @Test
    fun aBlankQueryIsNoSearch() {
        val games = listOf(crash, game("Spyro"))

        assertNull(LibrarySearch.query("   "))
        assertNull(LibrarySearch.query(""))
        assertEquals("crash", LibrarySearch.query("  crash "))
        assertEquals(games, LibrarySearch.filter(SearchIndex(games), null))
        assertEquals(listOf(crash), LibrarySearch.filter(SearchIndex(games), "crash"))
    }

    @Test
    fun matchGivesTheRange() {
        assertEquals(6..9, LibrarySearch.match("Crash Bandicoot", "band"))
        assertNull(LibrarySearch.match("Crash Bandicoot", "spyro"))
        assertNull(LibrarySearch.match("Crash Bandicoot", null))
    }

    @Test
    fun ignoresCaseBeyondAscii() {
        assertEquals(0..4, LibrarySearch.match("ÄRGER im Spiel", "ärger"))
        assertEquals(2..3, LibrarySearch.match("ファイナルファンタジー", "イナ"))
        assertTrue(LibrarySearch.matches(game("Éclair"), "éCL"))
    }

    @Test
    fun countLines() {
        val title = LibrarySort()
        val last = LibrarySort(SortKey.LastPlayed)

        assertEquals("48 games · Title, A–Z", LibraryWords.countLine(48, 48, searching = false, sort = title))
        assertEquals("2 of 48 games · Title, A–Z", LibraryWords.countLine(2, 48, searching = true, sort = title))
        assertEquals("1 game · Title, A–Z", LibraryWords.countLine(1, 1, searching = false, sort = title))
        assertEquals("0 of 1 game · Title, A–Z", LibraryWords.countLine(0, 1, searching = true, sort = title))
        assertEquals(
            "48 games · Last played, most recent first",
            LibraryWords.countLine(48, 48, searching = false, sort = last),
        )
    }

    @Test
    fun aThousandGamesFilterInAFrame() {
        // As the storage provider names them: every file name in the path is escaped, so it needs decoding.
        fun file(name: String) =
            "content://com.android.externalstorage.documents/tree/primary%3APS1/document/primary%3APS1%2F" +
                "Games%20(USA)%2F" + name.replace(" ", "%20").replace("(", "%28").replace(")", "%29")
        val games =
            (1..1000).map { n ->
                val title = "Game %04d".format(n)
                val serial = "SLUS-%05d".format(n)
                val discs = (1..3).map { GameDisc(file("$title (USA) (Disc $it).cue"), serial, title, it) }
                game(title, serial = serial, path = file("$title (USA).m3u"), discs = discs)
            }

        fun median(work: () -> Unit): Double {
            repeat(50) { work() }
            val times =
                (1..25).map {
                    val start = System.nanoTime()
                    work()
                    System.nanoTime() - start
                }
            return times.sorted()[times.size / 2] / 1_000_000.0
        }
        val index = SearchIndex(games)
        val filtering = median { LibrarySearch.filter(index, "game 09") }

        assertEquals(100, LibrarySearch.filter(index, "game 09").size)
        // A frame's budget at 60 Hz: a bound a shared CI runner keeps (a tighter one failed there now and then).
        assertTrue("took $filtering ms", filtering < 16.0)
    }

    // Each search used to decode the 4000 file names again; a timing bound against the index's own time caught it, but
    // failed now and then on CI's shared runners. Counted instead: once per games list, at the first search.
    @Test
    fun aSearchDecodesTheFileNamesOncePerList() {
        val games = (1..3).map { game("Game $it", path = "content://tree/document/Game%20$it.cue") }
        var decoded = 0
        val index =
            SearchIndex(games) {
                decoded++
                LibrarySearch.fileNames(it)
            }

        LibrarySearch.filter(index, "game")
        LibrarySearch.filter(index, "game 2")
        LibrarySearch.filter(index, "2")

        assertEquals(3, decoded)
    }
}
