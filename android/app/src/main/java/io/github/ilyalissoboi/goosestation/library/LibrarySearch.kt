// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import io.github.ilyalissoboi.goosestation.core.GameEntry

/**
 * The library's search: a game matches when its title, its file's name or one of its discs', or its key or one of its
 * discs' serials holds the query anywhere, ignoring case. Case is compared a character at a time, so a match's range
 * is in the text as shown.
 */
object LibrarySearch {
    /** What [text] searches for: trimmed, or null when it's blank, which is no search. */
    fun query(text: CharSequence): String? = text.trim().toString().ifEmpty { null }

    fun matches(
        game: GameEntry,
        query: String,
    ): Boolean =
        game.title.contains(query, ignoreCase = true) ||
            game.serial.contains(query, ignoreCase = true) ||
            GameFiles.fileName(game.path).contains(query, ignoreCase = true) ||
            game.discs.any {
                it.serial.contains(query, ignoreCase = true) ||
                    GameFiles.fileName(it.path).contains(query, ignoreCase = true)
            }

    /** The [games] matching [query], in their order; all of them without a query. */
    fun filter(
        games: List<GameEntry>,
        query: String?,
    ): List<GameEntry> = if (query == null) games else games.filter { matches(it, query) }

    /** Where [query] first is in [text], to mark it; null without a query or a match. */
    fun match(
        text: String,
        query: String?,
    ): IntRange? {
        if (query == null) return null
        val start = text.indexOf(query, ignoreCase = true)
        return if (start < 0) null else start until start + query.length
    }
}
