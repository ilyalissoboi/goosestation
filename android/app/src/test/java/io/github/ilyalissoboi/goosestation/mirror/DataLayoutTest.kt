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
            "playtime.dat",
            "custom_properties.ini",
            "memcards/a.mcd",
            "covers/x.png",
            "bios/ps-30j.bin",
            "savestates/sub/SLUS-00001_1.sav",
        ).forEach { assertTrue(it, default.mirrored(it)) }
    }

    // The RetroAchievements token is in the Keystore on Android: desktop's secrets.ini is its own, never copied in or
    // out, but it's GooseStation's, so it raises no "not GooseStation's" banner.
    @Test
    fun secretsIsOursButNotMirrored() {
        assertFalse(default.mirrored("secrets.ini"))
        assertFalse(default.stray("secrets.ini"))
        assertEquals(emptyList<String>(), default.others(listOf("secrets.ini" to false)))
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
        assertFalse(layout.mirrored("shared/other.bin"))
        assertFalse(layout.stray("shared/one.mcd"))
        assertTrue(layout.stray("shared/other.bin"))
        // shared/ is listed to reach the card, but nothing under it beyond the card, nor any other folder.
        assertTrue(layout.walks("shared"))
        assertFalse(layout.walks("shared/sub"))
        assertFalse(layout.walks("games"))
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

    @Test
    fun theCoresSaveStateTempFilesStayOnTheDevice() {
        assertTrue(default.mirrored("savestates/SCUS-94900_1.sav"))
        assertFalse(default.mirrored("savestates/SCUS-94900_1.sav.a1B2c3"))
        assertFalse(default.stray("savestates/SCUS-94900_1.sav.a1B2c3"))
        // Wherever the save states folder is.
        val moved = DataLayout.withCorePaths(listOf("states/"), null)
        assertFalse(moved.mirrored("states/SCUS-94900_resume.sav.Zz09aB"))
        // Only a save state's: other names keep their dots.
        assertTrue(default.mirrored("memcards/x.mcd.a1B2c3"))
        assertTrue(default.mirrored("savestates/SCUS-94900_1.sav.backup1"))
    }

    // emusync's data is this device's sync identity and key: never mirrored, never deleted as a stray, and a copy in the
    // data folder from an earlier build is neither pulled nor reported as something else's.
    @Test
    fun emusyncIsKeptAndNotOthers() {
        val core = DataLayout.withCorePaths(listOf("memcards/"), cacheFolder = null)
        for (layout in listOf(default, core)) {
            assertFalse(layout.mirrored("emusync/ab12cd34ef56ab78/db.sqlite"))
            assertFalse(layout.stray("emusync/ab12cd34ef56ab78/db.sqlite"))
            assertFalse(layout.walks("emusync"))
            assertEquals(emptyList<String>(), layout.others(listOf("emusync" to true)))
        }
    }

    // emusync writes a card to <card>.emusync-tmp, then renames it over the card; a folder card has -old and -swap too.
    @Test
    fun emusyncScratchIsKept() {
        listOf(
            "memcards/shared_card_1.mcd.emusync-tmp",
            "memcards/x.emusync-old/save",
            "memcards/x.emusync-swap",
        ).forEach {
            assertFalse(it, default.mirrored(it))
            assertFalse(it, default.stray(it))
        }
        assertTrue(default.mirrored("memcards/emusync-tmp.mcd"))
    }

    // With Log to file on, the core names its logs: they're mirrored to the data folder then, and only then.
    @Test
    fun theLogIsMirroredOnlyWhenTheCoreNamesIt() {
        assertFalse(DataLayout.DEFAULT.mirrored("goosestation.log"))
        assertFalse(DataLayout.DEFAULT.mirrored("goosestation-previous.log"))
        assertFalse(DataLayout.withCorePaths(listOf("memcards/"), cacheFolder = "cache/").mirrored("goosestation.log"))

        val logging =
            DataLayout.withCorePaths(
                listOf("memcards/", "goosestation.log", "goosestation-previous.log"),
                cacheFolder = "cache/",
            )

        assertTrue(logging.mirrored("goosestation.log"))
        assertTrue(logging.mirrored("goosestation-previous.log"))
        assertFalse(logging.stray("goosestation.log"))
    }

    // The data root itself in the layout (a data folder of the core's own) still leaves the log out while it's off.
    @Test
    fun aRootLayoutStillKeepsTheLogUnlessNamed() {
        assertFalse(DataLayout.withCorePaths(listOf(""), cacheFolder = "cache/").mirrored("goosestation-previous.log"))
    }

    // A desktop's shader packs stay on the desktop: the files its markers list, and the markers, never come through.
    @Test
    fun packFilesAndMarkersAreNeitherMirroredNorKept() {
        val layout =
            DataLayout
                .withCorePaths(listOf("shaders/"), null)
                .withPackFiles("shaders/.shaderpacks/", setOf("shaders/shaders_slang/crt/a.slangp"))

        for (path in listOf("shaders/.shaderpacks/shaders_slang.json", "shaders/shaders_slang/crt/a.slangp")) {
            assertFalse(path, layout.mirrored(path))
            assertTrue(path, layout.stray(path))
        }
        assertTrue(layout.mirrored("shaders/shaders_slang/mine.slangp"))
        assertTrue(layout.mirrored("shaders/crt-lottes.glsl"))
        assertFalse(layout.stray("shaders/shaders_slang/mine.slangp"))
        // Still walked: the player's own shaders sit beside a pack's.
        assertTrue(layout.walks("shaders/shaders_slang"))
        assertTrue(layout.fromCore)
    }

    @Test
    fun withoutMarkersNothingChanges() {
        val layout =
            DataLayout
                .withCorePaths(
                    listOf("shaders/"),
                    null,
                ).withPackFiles("shaders/.shaderpacks/", emptySet())
        assertTrue(layout.mirrored("shaders/shaders_slang/crt/a.slangp"))
        assertFalse(layout.mirrored("shaders/.shaderpacks/shaders_slang.json"))
    }
}
