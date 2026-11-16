// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TexturePacksTest {
    private val data = "content://com.android.externalstorage.documents/tree/primary%3AGooseStation"

    @Test
    fun packsAreReadFromTheDataFolder() {
        assertEquals(data, TexturePacks.folderFor(data, granted = true))
    }

    @Test
    fun withoutTheDataFoldersGrantPacksAreReadFromNowhere() {
        assertEquals("", TexturePacks.folderFor(data, granted = false))
        assertEquals("", TexturePacks.folderFor(null, granted = true))
    }
}
