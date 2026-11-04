// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The file picker of the activity shown, or none: what the memory card editor reads and writes through. */
class CurrentDocumentsTest {
    private val written = mutableMapOf<String, ByteArray>()
    private val picker =
        object : Documents {
            override suspend fun open() = PickedDocument("content://save", "save.mcs")

            override suspend fun create(suggestedName: String) = "content://new/$suggestedName"

            override fun read(
                uri: String,
                limit: Int,
            ) = DocumentRead.Bytes(ByteArray(3))

            override fun write(
                uri: String,
                bytes: ByteArray,
            ): String? {
                written[uri] = bytes
                return null
            }
        }

    @Test
    fun aWriteThatSucceedsSaysSo() {
        val documents = CurrentDocuments { picker }

        assertNull(documents.write("content://out", ByteArray(2)))
        assertEquals(2, written["content://out"]?.size)
    }

    @Test
    fun itGoesToThePickerShown() =
        runTest {
            val documents = CurrentDocuments { picker }

            assertEquals("save.mcs", documents.open()?.displayName)
            assertEquals("content://new/x.mcs", documents.create("x.mcs"))
            assertTrue(documents.read("content://save", 10) is DocumentRead.Bytes)
        }

    @Test
    fun withoutAPickerNothingIsPickedOrWritten() =
        runTest {
            val documents = CurrentDocuments { null }

            assertNull(documents.open())
            assertNull(documents.create("x.mcs"))
            assertTrue(documents.read("content://save", 10) is DocumentRead.Failed)
            assertEquals(CurrentDocuments.NOT_SHOWN, documents.write("content://out", ByteArray(2)))
        }
}
