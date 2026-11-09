// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import io.github.ilyalissoboi.goosestation.core.GameEntry

/** What the library sorts by: Big Picture's `[Main] FullscreenUIGameSort`, whose number each one is ([value]). */
enum class SortKey(
    val value: Int,
) {
    Type(0),
    Serial(1),
    Title(2),
    FileTitle(3),
    TimePlayed(4),
    LastPlayed(5),
    FileSize(6),
    UncompressedSize(7),
    ;

    companion object {
        /** The sort a key's [value] names; one Big Picture doesn't know sorts by title alone, as there. */
        fun of(value: Int): SortKey = entries.firstOrNull { it.value == value } ?: Title

        /** The sorts the library offers, in its list's order: Big Picture's, less Type and Uncompressed size. */
        val OFFERED: List<SortKey> = listOf(Title, LastPlayed, TimePlayed, Serial, FileTitle, FileSize)
    }
}

/** The library's sort: its [key], and whether its order is [reverse]d (`[Main] FullscreenUIGameSortReverse`). */
data class LibrarySort(
    val key: SortKey = SortKey.Title,
    val reverse: Boolean = false,
)

/**
 * Sorts the library as Big Picture does (`FullscreenUI::PopulateGameListEntryList`), so a data folder shared with
 * desktop orders alike: Title, Serial and File title from A, Time played and the sizes least first, Last played most
 * recent first, Type discs first; reversed, each the other way. Ties fall back to the title, reversed with the rest.
 * Titles and file titles compare as `strcasecmp` does, folding ASCII letters only; serials compare as bytes.
 */
object LibraryOrder {
    fun sorted(
        games: List<GameEntry>,
        sort: LibrarySort,
    ): List<GameEntry> {
        // File titles come from decoding paths: once a game, not once a comparison.
        val fileTitles = if (sort.key == SortKey.FileTitle) games.map { GameFiles.fileTitle(it.path) } else null
        val order =
            mergeSort(games.size) { a, b -> compare(games[a], games[b], sort, fileTitles?.get(a), fileTitles?.get(b)) }
        return order.map { games[it] }
    }

    /**
     * The indices 0 until [size] in [compare]'s order, stably, by merging. Not the platform's sort: Big Picture's File
     * title comparison isn't consistent ("Crash" ties with both "Crash 2" and "Crasha", which don't tie with each
     * other), and TimSort throws when it sees that. A merge never checks, so the order is Big Picture's comparison's
     * wherever that has one.
     */
    private fun mergeSort(
        size: Int,
        compare: (Int, Int) -> Int,
    ): IntArray {
        var from = IntArray(size) { it }
        var to = IntArray(size)
        var width = 1
        while (width < size) {
            var start = 0
            while (start < size) {
                val middle = minOf(start + width, size)
                val end = minOf(start + 2 * width, size)
                var i = start
                var j = middle
                for (k in start until end) {
                    to[k] = if (i < middle && (j >= end || compare(from[i], from[j]) <= 0)) from[i++] else from[j++]
                }
                start = end
            }
            from = to.also { to = from }
            width *= 2
        }
        return from
    }

    private fun compare(
        a: GameEntry,
        b: GameEntry,
        sort: LibrarySort,
        aFileTitle: String?,
        bFileTitle: String?,
    ): Int {
        val first =
            when (sort.key) {
                SortKey.Type -> a.typeOrder.compareTo(b.typeOrder)
                SortKey.Serial -> compareCodePoints(a.serial, b.serial, foldAscii = false, prefixOnly = false)
                SortKey.Title -> 0
                SortKey.FileTitle ->
                    compareCodePoints(aFileTitle.orEmpty(), bFileTitle.orEmpty(), foldAscii = true, prefixOnly = true)
                SortKey.TimePlayed -> a.totalPlayedSeconds.compareTo(b.totalPlayedSeconds)
                // Most recent first unless reversed.
                SortKey.LastPlayed -> b.lastPlayedSeconds.compareTo(a.lastPlayedSeconds)
                SortKey.FileSize -> a.fileSize.compareTo(b.fileSize)
                SortKey.UncompressedSize -> a.uncompressedSize.compareTo(b.uncompressedSize)
            }
        val order = if (first != 0) first else compareCodePoints(a.title, b.title, foldAscii = true, prefixOnly = false)
        return if (sort.reverse) -order else order
    }

    /**
     * [a] against [b] a code point at a time, which orders as their UTF-8 bytes do; with [foldAscii], ASCII letters
     * lower-cased first, as `strcasecmp` does. With [prefixOnly], only up to the shorter one's end, as Big Picture's
     * `strncasecmp` over the shorter length: one that starts the other ties with it.
     */
    private fun compareCodePoints(
        a: String,
        b: String,
        foldAscii: Boolean,
        prefixOnly: Boolean,
    ): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            val fx = if (foldAscii && x in 'A'.code..'Z'.code) x + ('a' - 'A') else x
            val fy = if (foldAscii && y in 'A'.code..'Z'.code) y + ('a' - 'A') else y
            if (fx != fy) return fx.compareTo(fy)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return if (prefixOnly) 0 else (i < a.length).compareTo(j < b.length)
    }
}
