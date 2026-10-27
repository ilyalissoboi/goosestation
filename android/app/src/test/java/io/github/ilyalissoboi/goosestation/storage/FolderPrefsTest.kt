// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class FolderPrefsTest {
    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(
            key: String,
            value: String?,
        ): Boolean {
            if (value == null) values.remove(key) else values[key] = value
            return true
        }
    }

    @Test
    fun saveThenLoadKeepsGameFoldersInOrder() {
        val store = MemoryStore()
        val saved =
            SavedFolders(
                bios = "content://a.b/tree/bios",
                games = listOf("content://a.b/tree/z", "content://a.b/tree/a", "content://a.b/tree/m"),
                data = "content://a.b/tree/data",
                setupComplete = true,
            )

        FolderPrefs(store).save(saved)

        assertEquals(saved, FolderPrefs(store).load())
    }

    @Test
    fun installIdIsStable() {
        val store = MemoryStore()

        val first = FolderPrefs(store).installId()
        val second = FolderPrefs(store).installId()

        assertEquals(first, second)
        assertEquals(first, UUID.fromString(first).toString())
    }

    @Test
    fun usesTellsWhetherAnySlotHoldsAUri() {
        val saved = SavedFolders("content://a.b/tree/x", listOf("content://a.b/tree/g"), "content://a.b/tree/d", true)

        assertTrue(saved.uses("content://a.b/tree/x"))
        assertTrue(saved.uses("content://a.b/tree/g"))
        assertTrue(saved.uses("content://a.b/tree/d"))
        assertFalse(saved.uses("content://a.b/tree/g/"))
    }

    @Test
    fun biosImagesAreKeptInOrder() {
        val store = MemoryStore()
        assertEquals(emptyList<String>(), FolderPrefs(store).biosImages())

        assertTrue(FolderPrefs(store).saveBiosImages(listOf("SCPH-7001 (v4.1)", "SCPH-1001 (v2.2)")))

        assertEquals(listOf("SCPH-7001 (v4.1)", "SCPH-1001 (v2.2)"), FolderPrefs(store).biosImages())
    }
}
