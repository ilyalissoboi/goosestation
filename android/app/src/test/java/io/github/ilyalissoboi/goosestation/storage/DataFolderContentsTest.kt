// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataFolderContentsTest {
    private class FakeLister(
        private val folders: Map<String, List<NativeDocument>?>,
    ) : DocumentLister {
        override fun listChildren(
            treeUri: String,
            parentDocumentId: String,
        ) = folders[parentDocumentId]

        override fun rootDocumentId(treeUri: String) = "root"
    }

    private fun file(name: String) = NativeDocument(name, "root/$name", 1, 1, isDirectory = false)

    private fun folder(name: String) = NativeDocument(name, name, 0, 0, isDirectory = true)

    @Test
    fun emptyFolderStartsFresh() {
        val contents = DataFolderContents.read(FakeLister(mapOf("root" to emptyList())), "tree")

        assertEquals(DataFolderContents(hasSettings = false, memoryCards = 0), contents)
        assertEquals("This folder is empty. GooseStation will start fresh here.", contents!!.describe())
    }

    @Test
    fun settingsAndMemoryCardsAreFound() {
        val lister =
            FakeLister(
                mapOf(
                    "root" to listOf(file("settings.ini"), folder("memcards"), folder("covers")),
                    "memcards" to listOf(file("a.mcd"), file("b.mcd"), folder("old")),
                ),
            )

        val contents = DataFolderContents.read(lister, "tree")

        assertEquals(DataFolderContents(hasSettings = true, memoryCards = 2), contents)
        assertEquals(
            "This folder already has GooseStation data (settings and 2 memory cards). GooseStation will use it.",
            contents!!.describe(),
        )
    }

    @Test
    fun anEmptyMemcardsFolderIsNoData() {
        val lister = FakeLister(mapOf("root" to listOf(folder("memcards")), "memcards" to emptyList()))

        assertEquals(DataFolderContents(hasSettings = false, memoryCards = 0), DataFolderContents.read(lister, "tree"))
    }

    @Test
    fun anUnreadableFolderIsNull() {
        assertNull(DataFolderContents.read(FakeLister(mapOf("root" to null)), "tree"))
    }

    @Test
    fun summarySaysOnlyWhatTheFolderHolds() {
        assertEquals("This folder is empty.", DataFolderContents(hasSettings = false, memoryCards = 0).summary())
        assertEquals(
            "This folder already has GooseStation data (1 memory card).",
            DataFolderContents(hasSettings = false, memoryCards = 1).summary(),
        )
    }

    @Test
    fun otherThingsAreToldAndLeftAlone() {
        val lister =
            FakeLister(
                mapOf(
                    "root" to
                        listOf(folder("games"), folder("bios"), file(".goosestation-probe.goosetmp"), folder(".x")),
                ),
            )

        val contents = DataFolderContents.read(lister, "tree")!!

        assertEquals(DataFolderContents(hasSettings = false, memoryCards = 0, others = listOf("games")), contents)
        assertEquals(
            "No GooseStation data here yet. GooseStation will start fresh in this folder.",
            contents.describe(),
        )
        assertEquals("No GooseStation data here yet.", contents.summary())
        assertEquals(
            "This folder also holds other things (games). GooseStation leaves them alone.",
            contents.otherThings(),
        )
    }

    @Test
    fun otherThingsBesideGooseStationData() {
        val contents = DataFolderContents(hasSettings = true, memoryCards = 0, others = listOf("games", "notes.txt"))

        assertEquals(
            "This folder already has GooseStation data (settings). GooseStation will use it.",
            contents.describe(),
        )
        assertEquals(
            "This folder also holds other things (games, notes.txt). GooseStation leaves them alone.",
            contents.otherThings(),
        )
    }

    @Test
    fun otherThingsListUpToThreeNames() {
        val contents = DataFolderContents(false, 0, others = listOf("a", "b", "c", "d", "e"))

        assertEquals(
            "This folder also holds other things (a, b, c and 2 more). GooseStation leaves them alone.",
            contents.otherThings(),
        )
        assertEquals(
            "This folder also holds other things (a, b, c). GooseStation leaves them alone.",
            DataFolderContents(false, 0, others = listOf("a", "b", "c")).otherThings(),
        )
    }

    @Test
    fun noOtherThingsNoLine() {
        assertNull(DataFolderContents(hasSettings = true, memoryCards = 1).otherThings())
    }
}
