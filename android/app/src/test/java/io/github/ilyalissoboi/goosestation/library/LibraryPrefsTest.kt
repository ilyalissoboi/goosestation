// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import io.github.ilyalissoboi.goosestation.settings.MemoryCoreSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** The library's sort in Big Picture's keys, so a data folder shared with desktop sorts alike. */
class LibraryPrefsTest {
    @Test
    fun absentKeysReadAsTitleAToZ() {
        // Not Big Picture's own default, Type: Android's is Title until the player picks.
        assertEquals(LibrarySort(SortKey.Title, reverse = false), LibraryPrefs.read(MemoryCoreSettings()))
    }

    @Test
    fun desktopValuesReadAsTheyAre() {
        val settings = MemoryCoreSettings()
        settings.values["Main/FullscreenUIGameSort"] = "0"
        settings.values["Main/FullscreenUIGameSortReverse"] = "true"
        assertEquals(LibrarySort(SortKey.Type, reverse = true), LibraryPrefs.read(settings))

        settings.values["Main/FullscreenUIGameSort"] = "7"
        assertEquals(LibrarySort(SortKey.UncompressedSize, reverse = true), LibraryPrefs.read(settings))

        settings.values["Main/FullscreenUIGameSort"] = "12"
        assertEquals(SortKey.Title, LibraryPrefs.read(settings).key)
    }

    @Test
    fun writeSetsBothKeys() {
        val settings = MemoryCoreSettings()

        LibraryPrefs.write(settings, LibrarySort(SortKey.LastPlayed, reverse = true))

        assertEquals("5", settings.values["Main/FullscreenUIGameSort"])
        assertEquals("true", settings.values["Main/FullscreenUIGameSortReverse"])
        assertEquals(LibrarySort(SortKey.LastPlayed, reverse = true), LibraryPrefs.read(settings))
    }
}
