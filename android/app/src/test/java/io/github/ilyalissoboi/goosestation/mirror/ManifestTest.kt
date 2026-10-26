// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManifestTest {
    private val manifest =
        Manifest(
            mapOf(
                "memcards/a.mcd" to
                    ManifestEntry(FileState(131072, 1_700_000_000_000), FileState(131072, 1_600_000_000_000)),
                "odd\tname\nwith\\slash.ini" to ManifestEntry(FileState(0, 0), FileState(12, -1)),
                "covers/Final Fantasy #1 (100%).png" to ManifestEntry(FileState(5, 6), FileState(7, 8)),
            ),
        )

    @Test
    fun roundTripKeepsEveryEntry() {
        assertEquals(manifest, Manifest.decode(manifest.encode()))
    }

    @Test
    fun encodeEscapesPathsAndSeparatesFieldsWithTabs() {
        val text = Manifest(mapOf("a\tb\nc\\d" to ManifestEntry(FileState(1, 2), FileState(3, 4)))).encode()

        assertEquals("goosestation-mirror 1\na\\tb\\nc\\\\d\t1\t2\t3\t4\n", text)
    }

    @Test
    fun headerAloneIsEmpty() {
        assertEquals(Manifest(emptyMap()), Manifest.decode("goosestation-mirror 1\n"))
    }

    @Test
    fun garbageIsNull() {
        assertNull(Manifest.decode("garbage"))
    }

    @Test
    fun anyMalformedLineIsNull() {
        val header = "goosestation-mirror 1\n"
        listOf(
            "",
            "goosestation-mirror 2\n",
            header + "a\t1\t2\t3\n",
            header + "a\t1\t2\t3\t4\t5\n",
            header + "a\t1\tx\t3\t4\n",
            header + "\t1\t2\t3\t4\n",
            header + "a\\q\t1\t2\t3\t4\n",
            header + "a\\\t1\t2\t3\t4\n",
            header + "a\t1\t2\t3\t4\n\n",
            header + "a\t1\t2\t3\t4\na\t1\t2\t3\t4\n",
        ).forEach { assertNull(it, Manifest.decode(it)) }
    }
}
