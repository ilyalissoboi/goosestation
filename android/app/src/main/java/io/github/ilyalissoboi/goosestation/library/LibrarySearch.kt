// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import io.github.ilyalissoboi.goosestation.core.GameEntry

/**
 * [games] ready to search: each one's file name and its discs', decoded once for the list, at its first search, rather
 * than on every keystroke, which a thousand games' content URIs would make slow.
 */
class SearchIndex(
    val games: List<GameEntry>,
) {
    internal val fileNames: List<List<String>> by lazy { games.map(LibrarySearch::fileNames) }
}

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
    ): Boolean = matches(game, fileNames(game), query)

    private fun matches(
        game: GameEntry,
        fileNames: List<String>,
        query: String,
    ): Boolean =
        game.title.contains(query, ignoreCase = true) ||
            game.serial.contains(query, ignoreCase = true) ||
            game.discs.any { it.serial.contains(query, ignoreCase = true) } ||
            fileNames.any { it.contains(query, ignoreCase = true) }

    /** The [index]'s games matching [query], in their order; all of them without a query. */
    fun filter(
        index: SearchIndex,
        query: String?,
    ): List<GameEntry> =
        if (query == null) {
            index.games
        } else {
            index.games.filterIndexed { i, game -> matches(game, index.fileNames[i], query) }
        }

    internal fun fileNames(game: GameEntry): List<String> =
        listOf(GameFiles.fileName(game.path)) + game.discs.map { GameFiles.fileName(it.path) }

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
