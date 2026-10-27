// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class GrantCheckTest {
    private val bios = "content://a.b/tree/primary%3APS1%2Fbios"
    private val gamesA = "content://a.b/tree/primary%3APS1%2FGames"
    private val gamesB = "content://a.b/tree/1234-ABCD%3ARoms"
    private val data = "content://a.b/tree/primary%3APS1%2Fdata"
    private val saved = SavedFolders(bios = bios, games = listOf(gamesA, gamesB), data = data, setupComplete = true)

    private fun read(uri: String) = PersistedGrant(uri, read = true, write = false)

    private fun readWrite(uri: String) = PersistedGrant(uri, read = true, write = true)

    @Test
    fun allGrantedIsNothingMissing() {
        val persisted = listOf(read(bios), read(gamesA), read(gamesB), readWrite(data))

        assertEquals(emptyList<MissingGrant>(), GrantCheck.missing(saved, persisted))
    }

    @Test
    fun readOnlyDataIsMissing() {
        val persisted = listOf(read(bios), read(gamesA), read(gamesB), read(data))

        assertEquals(listOf(MissingGrant(FolderKind.Data, data)), GrantCheck.missing(saved, persisted))
    }

    @Test
    fun gamesUriMatchesOnlyExactly() {
        val persisted = listOf(read(bios), read("$gamesA/"), read(gamesB.uppercase()), readWrite(data))

        assertEquals(
            listOf(MissingGrant(FolderKind.Games, gamesA), MissingGrant(FolderKind.Games, gamesB)),
            GrantCheck.missing(saved, persisted),
        )
    }

    @Test
    fun parentTreeGrantDoesNotCount() {
        val parent = "content://a.b/tree/primary%3APS1"
        val persisted = listOf(read(bios), read(gamesA), read(gamesB), readWrite(parent))

        assertEquals(listOf(MissingGrant(FolderKind.Data, data)), GrantCheck.missing(saved, persisted))
    }

    @Test
    fun missingComeBackInBiosGamesDataOrder() {
        assertEquals(
            listOf(
                MissingGrant(FolderKind.Bios, bios),
                MissingGrant(FolderKind.Games, gamesA),
                MissingGrant(FolderKind.Games, gamesB),
                MissingGrant(FolderKind.Data, data),
            ),
            GrantCheck.missing(saved, emptyList()),
        )
    }
}
