// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameEntry

/**
 * A library game as its memory card is found: its [title], and the [serial] and [bootPath] it boots with by default,
 * from its first disc, and whether that's a [playlist].
 */
data class MemcardGame(
    val title: String,
    val serial: String,
    val bootPath: String,
    val playlist: Boolean,
)

object MemcardGames {
    /**
     * The [games] as their cards are found, by title ignoring case. A game boots from its playlist, or else its first
     * disc, or its own file when it has no discs, with that disc's serial, or the game's key when the disc has none.
     */
    fun of(games: List<GameEntry>): List<MemcardGame> =
        games
            .map { game ->
                val first = game.discs.firstOrNull()
                MemcardGame(
                    title = game.title,
                    serial = first?.serial?.takeIf { it.isNotBlank() } ?: game.serial,
                    bootPath = if (game.playlist || first == null) game.path else first.path,
                    playlist = game.playlist,
                )
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}
