// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataFolderCheckTest {
    private fun tree(
        documentId: String,
        authority: String = "com.android.externalstorage.documents",
    ) = "content://$authority/tree/" + documentId.replace(":", "%3A").replace("/", "%2F").replace(" ", "%20")

    private fun overlaps(
        a: String,
        b: String,
    ) = DataFolderCheck.overlaps(a, b).also { assertTrue("not symmetric", it == DataFolderCheck.overlaps(b, a)) }

    @Test
    fun theSameFolderOverlaps() {
        assertTrue(overlaps(tree("primary:PS1/games"), tree("primary:PS1/games")))
    }

    @Test
    fun aFolderInsideAnotherOverlaps() {
        assertTrue(overlaps(tree("primary:PS1/games/data"), tree("primary:PS1/games")))
        assertTrue(overlaps(tree("primary:PS1"), tree("primary:PS1/games")))
        assertTrue(overlaps(tree("primary:PS1 Stuff/My Games/a"), tree("primary:PS1 Stuff/My Games")))
    }

    @Test
    fun aStorageRootHoldsEveryFolderOnIt() {
        assertTrue(overlaps(tree("primary:"), tree("primary:PS1/games")))
        assertFalse(overlaps(tree("1234-5678:"), tree("primary:PS1/games")))
    }

    @Test
    fun aNameSharingTheStartIsNotInside() {
        assertFalse(overlaps(tree("primary:PS1/games2"), tree("primary:PS1/games")))
        assertFalse(overlaps(tree("primary:PS1/games data"), tree("primary:PS1/games")))
    }

    @Test
    fun siblingsAndOtherStoragesDoNotOverlap() {
        assertFalse(overlaps(tree("primary:PS1/data"), tree("primary:PS1/games")))
        assertFalse(overlaps(tree("1234-5678:PS1/games"), tree("primary:PS1/games")))
    }

    @Test
    fun anotherProviderNeverOverlaps() {
        assertFalse(overlaps(tree("primary:PS1/games", "com.example.docs"), tree("primary:PS1/games")))
    }

    // A cloud provider's opaque IDs say nothing of nesting: only the same folder is caught.
    @Test
    fun opaqueIdsOverlapOnlyWhenEqual() {
        val drive = "com.google.android.apps.docs.storage"
        assertTrue(overlaps(tree("acc=1;doc=encoded=abc", drive), tree("acc=1;doc=encoded=abc", drive)))
        assertFalse(overlaps(tree("acc=1;doc=encoded=abcd", drive), tree("acc=1;doc=encoded=abc", drive)))
    }

    @Test
    fun aDataFolderIsRefusedOnlyForAnOverlappingGamesFolder() {
        val games = listOf(tree("primary:Roms"), tree("primary:PS1/games"))

        assertTrue(DataFolderCheck.overlapsAny(tree("primary:PS1/games/saves"), games))
        assertFalse(DataFolderCheck.overlapsAny(tree("primary:PS1/data"), games))
        assertFalse(DataFolderCheck.overlapsAny(tree("primary:PS1/data"), emptyList()))
    }
}
