// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

/** The library's words for its sort and its count. */
object LibraryWords {
    fun sortName(key: SortKey): String =
        when (key) {
            SortKey.Type -> "Type"
            SortKey.Serial -> "Serial"
            SortKey.Title -> "Title"
            SortKey.FileTitle -> "File title"
            SortKey.TimePlayed -> "Time played"
            SortKey.LastPlayed -> "Last played"
            SortKey.FileSize -> "File size"
            SortKey.UncompressedSize -> "Uncompressed size"
        }

    /** Which way [sort] runs, as the Order button names it: "A to Z", "Most recent first". */
    fun direction(sort: LibrarySort): String {
        val (first, reversed) =
            when (sort.key) {
                SortKey.Title, SortKey.Serial, SortKey.FileTitle -> "A to Z" to "Z to A"
                SortKey.LastPlayed -> "Most recent first" to "Least recent first"
                SortKey.TimePlayed -> "Least played first" to "Most played first"
                SortKey.FileSize, SortKey.UncompressedSize -> "Smallest first" to "Largest first"
                SortKey.Type -> "Discs first" to "PSFs first"
            }
        return if (sort.reverse) reversed else first
    }

    /** [direction] as the count line puts it after the sort's name: "A–Z", "most recent first". */
    fun shortDirection(sort: LibrarySort): String =
        when (val words = direction(sort)) {
            "A to Z" -> "A–Z"
            "Z to A" -> "Z–A"
            // An acronym keeps its capitals.
            else -> if (words.startsWith("PSF")) words else words.replaceFirstChar { it.lowercase() }
        }
}
