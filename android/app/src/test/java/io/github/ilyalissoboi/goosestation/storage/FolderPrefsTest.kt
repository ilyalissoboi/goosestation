// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import org.junit.Assert.assertEquals
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
}
