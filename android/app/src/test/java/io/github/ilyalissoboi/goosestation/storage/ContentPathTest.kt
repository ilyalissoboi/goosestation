// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentPathTest {
    @Test
    fun splitsTreeFromRawNames() {
        val path = "content://a.b/tree/primary%3APS1/FF7/x y.cue"
        val parsed = ContentPath.parse(path)!!

        assertEquals("content://a.b/tree/primary%3APS1", parsed.treeUri)
        assertEquals(listOf("FF7", "x y.cue"), parsed.segments)
        assertEquals(path, parsed.toString())
    }

    @Test
    fun treeRootHasNoSegments() {
        val parsed = ContentPath.parse("content://a.b/tree/primary%3APS1")!!

        assertEquals("content://a.b/tree/primary%3APS1", parsed.treeUri)
        assertEquals(emptyList<String>(), parsed.segments)
        assertEquals("content://a.b/tree/primary%3APS1", parsed.toString())
    }

    @Test
    fun uriSyntaxInNamesStaysRaw() {
        val parsed = ContentPath.parse("content://a.b/tree/t/Disc?/Final Fantasy #1 (100%).cue")!!

        assertEquals("content://a.b/tree/t", parsed.treeUri)
        assertEquals(listOf("Disc?", "Final Fantasy #1 (100%).cue"), parsed.segments)
    }

    @Test
    fun dropsEmptyAndDotSegments() {
        val parsed = ContentPath.parse("content://a.b/tree/t//FF7/./disc.cue/")!!

        assertEquals(listOf("FF7", "disc.cue"), parsed.segments)
    }

    @Test
    fun rejectsWhatIsNotATreePath() {
        assertNull(ContentPath.parse("content://a/document/x"))
        assertNull(ContentPath.parse("content://a/tree/"))
        assertNull(ContentPath.parse("content://a/tree/x/../y"))
        assertNull(ContentPath.parse("/sdcard/x"))
    }
}
