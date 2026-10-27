// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/**
 * Where the memory cards are in the working copy, as the core says ([io.github.ilyalissoboi.goosestation.core.
 * NativeLibrary.getMemoryCardPaths]): relative paths, where one ending in `/` is a folder and the empty one is the
 * whole working copy, and any other one is a card file.
 */
object MemoryCardPaths {
    /** The core's default memory card folder, used until the core says otherwise. */
    val DEFAULT = listOf("memcards/")

    /** Whether the working-copy file at [path] is, or may be, a memory card in [paths]. */
    fun matches(
        path: String,
        paths: List<String>,
    ): Boolean = paths.any { if (isFolder(it)) path.startsWith(it) else path == it }

    /** Whether a pull must leave the working-copy file at [path] alone: a memory card in [paths], while [gameActive]. */
    fun deferred(
        path: String,
        paths: List<String>,
        gameActive: Boolean,
    ): Boolean = gameActive && matches(path, paths)

    /**
     * The paths the core [reported] now, plus the card files among the [previous] ones it no longer reports: a game's
     * own card (its `gamesettings/<serial>.ini`) is reported only while it runs, and stays a card for the rest of the
     * run. Folders aren't kept: settings.ini names the memory card folder.
     */
    fun merge(
        reported: List<String>,
        previous: List<String>,
    ): List<String> = (reported + previous.filterNot(::isFolder)).distinct()

    private fun isFolder(path: String) = path.isEmpty() || path.endsWith('/')
}
