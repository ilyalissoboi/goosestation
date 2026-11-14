// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameDisc
import io.github.ilyalissoboi.goosestation.core.testGame
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which serial and path each library game boots with, which its memory card is found by. */
class MemcardGamesTest {
    private fun game(
        title: String,
        serial: String,
        path: String,
        discs: List<GameDisc> = emptyList(),
        playlist: Boolean = false,
    ) = testGame(title, serial, path, discs = discs, playlist = playlist)

    @Test
    fun aSingleDiscBootsFromItsDisc() {
        val disc = GameDisc("content://games/crash.cue", "SCUS-94900", "Crash Bandicoot", 1)

        assertEquals(
            listOf(MemcardGame("Crash Bandicoot", "SCUS-94900", "content://games/crash.cue", playlist = false)),
            MemcardGames.of(listOf(game("Crash Bandicoot", "SCUS-94900", "content://games/crash.cue", listOf(disc)))),
        )
    }

    @Test
    fun aDiscSetBootsFromItsFirstDisc() {
        val discs =
            listOf(
                GameDisc("content://games/ff7-1.cue", "SCUS-94163", "Final Fantasy VII (Disc 1)", 1),
                GameDisc("content://games/ff7-2.cue", "SCUS-94164", "Final Fantasy VII (Disc 2)", 2),
            )

        val booted =
            MemcardGames.of(
                listOf(game("Final Fantasy VII", "SCUS-94163", "content://games/ff7-1.cue", discs)),
            )

        assertEquals(
            MemcardGame("Final Fantasy VII", "SCUS-94163", "content://games/ff7-1.cue", false),
            booted.single(),
        )
    }

    @Test
    fun aPlaylistBootsFromThePlaylistWithItsFirstDiscsSerial() {
        val discs =
            listOf(
                GameDisc("content://games/ff7-1.cue", "SCUS-94163", "Disc 1", 1),
                GameDisc("content://games/ff7-2.cue", "SCUS-94164", "Disc 2", 2),
            )

        val booted =
            MemcardGames.of(
                listOf(game("Final Fantasy VII", "SCUS-94163", "content://games/ff7.m3u", discs, playlist = true)),
            )

        assertEquals(MemcardGame("Final Fantasy VII", "SCUS-94163", "content://games/ff7.m3u", true), booted.single())
    }

    @Test
    fun aGameWithoutDiscsBootsFromItsOwnPath() {
        val booted = MemcardGames.of(listOf(game("padtest", "HASH-0123456789ABCDEF", "/sdcard/padtest.psexe")))

        assertEquals(MemcardGame("padtest", "HASH-0123456789ABCDEF", "/sdcard/padtest.psexe", false), booted.single())
    }

    @Test
    fun aDiscWithoutASerialTakesTheGamesKey() {
        val disc = GameDisc("content://games/homebrew.cue", "", "Homebrew", 1)

        val booted = MemcardGames.of(listOf(game("Homebrew", "HASH-FEDCBA9876543210", disc.path, listOf(disc))))

        assertEquals("HASH-FEDCBA9876543210", booted.single().serial)
    }

    @Test
    fun theyAreSortedByTitleIgnoringCase() {
        val games = listOf(game("zeta", "A", "/a"), game("Alpha", "B", "/b"), game("beta", "C", "/c"))

        assertEquals(listOf("Alpha", "beta", "zeta"), MemcardGames.of(games).map { it.title })
    }
}
