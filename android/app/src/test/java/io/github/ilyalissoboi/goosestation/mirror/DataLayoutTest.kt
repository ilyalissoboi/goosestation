// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLayoutTest {
    private val default = DataLayout.DEFAULT

    @Test
    fun goosestationsOwnFilesAreMirrored() {
        listOf(
            "settings.ini",
            "secrets.ini",
            "playtime.dat",
            "custom_properties.ini",
            "memcards/a.mcd",
            "covers/x.png",
            "bios/ps-30j.bin",
            "savestates/sub/SLUS-00001_1.sav",
            "emusync/state",
        ).forEach { assertTrue(it, default.mirrored(it)) }
    }

    @Test
    fun everythingElseIsNot() {
        listOf(
            "games/x.chd",
            "notes.txt",
            "cache/x",
            "memcards/a.mcd.goosetmp",
            "memcards/.a.mcd.goosetmp",
            "settings.ini.goosebak",
            "goosestation.log",
            "memcards",
            "covers2/x.png",
            "games/memcards/a.mcd",
            "mycheats/a.cht",
        ).forEach { assertFalse(it, default.mirrored(it)) }
    }

    @Test
    fun aFolderTheCoreReportsIsMirrored() {
        val layout = DataLayout.withCorePaths(listOf("mycheats/", "memcards/"), cacheFolder = "cache/")

        assertTrue(layout.mirrored("mycheats/a.cht"))
        assertTrue(layout.mirrored("memcards/a.mcd"))
        // The defaults stay.
        assertTrue(layout.mirrored("covers/x.png"))
        assertFalse(layout.mirrored("games/x.chd"))
        assertTrue(layout.fromCore)
        assertFalse(default.fromCore)
    }

    @Test
    fun aCardFileTheCoreReportsIsMirroredAlone() {
        val layout = DataLayout.withCorePaths(listOf("memcards/", "shared/one.mcd"), cacheFolder = "cache/")

        assertTrue(layout.mirrored("shared/one.mcd"))
        assertFalse(layout.mirrored("shared/two.mcd"))
        assertTrue(layout.walks("shared"))
    }

    @Test
    fun theDataRootAsAFolderMirrorsOnlyItsTopFiles() {
        val layout = DataLayout.withCorePaths(listOf(""), cacheFolder = "cache/")

        assertTrue(layout.mirrored("shared_card_1.mcd"))
        assertFalse(layout.mirrored("games/x.chd"))
        assertFalse(layout.mirrored("goosestation.log"))
        assertFalse(layout.walks("games"))
    }

    @Test
    fun onlyLayoutFoldersAreWalked() {
        val layout = DataLayout.withCorePaths(listOf("data/cheats/"), cacheFolder = "cache/")

        listOf("memcards", "covers", "bios", "memcards/sub", "data", "data/cheats", "data/cheats/x").forEach {
            assertTrue(it, layout.walks(it))
        }
        listOf("games", "cache", "cache/x", "data/other", "covers2").forEach { assertFalse(it, layout.walks(it)) }
        assertTrue(layout.mirrored("data/cheats/a.cht"))
        assertFalse(layout.mirrored("data/other/a.cht"))
    }

    @Test
    fun strayFilesAreTheOnesTheCoreNeverWrites() {
        val layout = DataLayout.withCorePaths(listOf("memcards/"), cacheFolder = "mycache/")

        listOf("games/x.chd", "notes.txt", "games/sub/y.bin").forEach { assertTrue(it, layout.stray(it)) }
        // Mirrored, or the core's own files that stay on the device: the cache, the log, the mirror's temporaries.
        listOf(
            "settings.ini",
            "memcards/a.mcd",
            "cache/gamelist.cache",
            "mycache/gamelist.cache",
            "goosestation.log",
            "memcards/.a.mcd.goosetmp",
            "x.goosebak",
        ).forEach { assertFalse(it, layout.stray(it)) }
        assertFalse(layout.mirrored("mycache/gamelist.cache"))
    }

    @Test
    fun theCacheWinsOverAFolderInsideIt() {
        val layout = DataLayout.withCorePaths(listOf("cache/covers/"), cacheFolder = "cache/")

        assertFalse(layout.mirrored("cache/covers/x.png"))
        assertFalse(layout.walks("cache"))
    }

    @Test
    fun aGrownLayoutIsTold() {
        assertFalse(DataLayout.withCorePaths(listOf("memcards/", "covers/"), "cache/").addsTo(default))
        assertTrue(DataLayout.withCorePaths(listOf("memcards/", "mycheats/"), "cache/").addsTo(default))
        assertTrue(DataLayout.withCorePaths(listOf("memcards/", "shared/one.mcd"), "cache/").addsTo(default))
    }

    @Test
    fun topLevelNamesOutsideTheLayoutAreOthers() {
        assertEquals(
            listOf("games", "notes.txt"),
            default.others(
                listOf(
                    "games" to true,
                    "bios" to true,
                    "settings.ini" to false,
                    "notes.txt" to false,
                    "cache" to true,
                    "goosestation.log" to false,
                    ".goosestation-probe.goosetmp" to false,
                    ".hidden" to true,
                    "memcards" to true,
                ),
            ),
        )
        // A file named as a layout folder, or a folder named as a layout file, isn't GooseStation's.
        assertEquals(
            listOf("memcards", "settings.ini"),
            default.others(listOf("memcards" to false, "settings.ini" to true)),
        )
    }
}
