// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentResolverTest {
    private val tree = "content://a.b/tree/root"

    /** A tree held in memory: document IDs are the paths from the root, which is "root". */
    private class FakeLister(private val children: Map<String, List<NativeDocument>>) : DocumentLister {
        val calls = mutableMapOf<String, Int>()

        override fun rootDocumentId(treeUri: String) = "root"

        override fun listChildren(treeUri: String, parentDocumentId: String): List<NativeDocument>? {
            calls[parentDocumentId] = (calls[parentDocumentId] ?: 0) + 1
            return children[parentDocumentId]
        }
    }

    private var now = 1_000_000L

    private fun file(parent: String, name: String) = NativeDocument(name, "$parent/$name", 10, 2000, false)

    private fun dir(parent: String, name: String) = NativeDocument(name, "$parent/$name", 0, 2000, true)

    private fun path(vararg segments: String) = ContentPath(tree, segments.toList())

    @Test
    fun siblingsShareOneListing() {
        val names = (1..20).map { "track $it.bin" }
        val lister =
            FakeLister(
                mapOf(
                    "root" to listOf(dir("root", "FF7")),
                    "root/FF7" to names.map { file("root/FF7", it) },
                ),
            )
        val resolver = DocumentResolver(lister) { now }

        for (name in names) assertEquals("root/FF7/$name", resolver.resolve(path("FF7", name))?.documentId)

        assertEquals(1, lister.calls["root/FF7"])
        assertEquals(1, lister.calls["root"])
    }

    @Test
    fun listsAgainAfterFiveSeconds() {
        val lister = FakeLister(mapOf("root" to listOf(file("root", "a.cue"))))
        val resolver = DocumentResolver(lister) { now }

        resolver.resolve(path("a.cue"))
        now += 4999
        resolver.resolve(path("a.cue"))
        assertEquals(1, lister.calls["root"])

        now += 1
        resolver.resolve(path("a.cue"))
        assertEquals(2, lister.calls["root"])
    }

    @Test
    fun singleCaseInsensitiveMatchIsUsed() {
        val lister = FakeLister(mapOf("root" to listOf(file("root", "Track 01.bin"), file("root", "Track 02.bin"))))
        val resolver = DocumentResolver(lister) { now }

        assertEquals("root/Track 01.bin", resolver.resolve(path("TRACK 01.BIN"))?.documentId)
    }

    @Test
    fun exactMatchWinsOverCaseVariants() {
        val lister = FakeLister(mapOf("root" to listOf(file("root", "a.BIN"), file("root", "A.bin"))))
        val resolver = DocumentResolver(lister) { now }

        assertEquals("root/A.bin", resolver.resolve(path("A.bin"))?.documentId)
    }

    @Test
    fun twoCaseVariantsWithoutExactMatchIsNull() {
        val lister = FakeLister(mapOf("root" to listOf(file("root", "a.BIN"), file("root", "A.bin"))))
        val resolver = DocumentResolver(lister) { now }

        assertNull(resolver.resolve(path("A.BIN")))
    }

    @Test
    fun invalidateForcesNewListing() {
        val lister = FakeLister(mapOf("root" to listOf(file("root", "a.cue"))))
        val resolver = DocumentResolver(lister) { now }

        resolver.resolve(path("a.cue"))
        resolver.invalidate()
        resolver.resolve(path("a.cue"))

        assertEquals(2, lister.calls["root"])
    }

    @Test
    fun treeRootIsADirectory() {
        val resolver = DocumentResolver(FakeLister(mapOf("root" to emptyList()))) { now }

        val root = resolver.resolve(path())

        assertNotNull(root)
        assertEquals("root", root!!.documentId)
        assertTrue(root.isDirectory)
    }

    @Test
    fun listGivesChildrenOfDirectoriesOnly() {
        val lister =
            FakeLister(
                mapOf(
                    "root" to listOf(dir("root", "FF7"), file("root", "a.cue")),
                    "root/FF7" to listOf(file("root/FF7", "disc 1.cue")),
                ),
            )
        val resolver = DocumentResolver(lister) { now }

        assertEquals(listOf("FF7", "a.cue"), resolver.list(path())?.map { it.name })
        assertEquals(listOf("disc 1.cue"), resolver.list(path("ff7"))?.map { it.name })
        assertNull(resolver.list(path("a.cue")))
        assertNull(resolver.list(path("missing")))
    }

    @Test
    fun nameUnderAFileIsNotFound() {
        val lister = FakeLister(mapOf("root" to listOf(file("root", "a.cue"))))
        val resolver = DocumentResolver(lister) { now }

        assertNull(resolver.resolve(path("a.cue", "b")))
        assertEquals(null, lister.calls["root/a.cue"])
    }

    @Test
    fun keepsAtMost64Listings() {
        val folders = (0 until 65).map { "d$it" }
        val lister =
            FakeLister(
                mapOf("root" to folders.map { dir("root", it) }) +
                    folders.associate { "root/$it" to listOf(file("root/$it", "x")) },
            )
        val resolver = DocumentResolver(lister) { now }

        // The root is used on every lookup, so it stays; d0 is the least recently used, and goes.
        for (folder in folders) resolver.resolve(path(folder, "x"))
        resolver.resolve(path("d64", "x"))
        resolver.resolve(path("d0", "x"))

        assertEquals(1, lister.calls["root"])
        assertEquals(1, lister.calls["root/d64"])
        assertEquals(2, lister.calls["root/d0"])
    }
}
