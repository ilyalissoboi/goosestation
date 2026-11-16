// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TexturePacksTest {
    private val data = "content://com.android.externalstorage.documents/tree/primary%3AGooseStation"

    // A grant missing at the core's start (a card not yet mounted) and given back later: the folder is read once it
    // can be, at the next game's start, without restarting the app. Until then it indexes nothing.
    @Test
    fun theDataFolderIsReadWhateverTheGrantIsAtStart() {
        assertEquals(data, TexturePacks.folderFor(data))
    }

    @Test
    fun withoutADataFolderPacksAreReadFromNowhere() {
        assertEquals("", TexturePacks.folderFor(null))
    }
}
