// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeMatchTest {
    private val gamesTree = "content://com.android.externalstorage.documents/tree/9C33-6BBD%3AGooseStationGames"

    @Test
    fun anSdCardsMountPathGivesItsVolumeId() {
        assertEquals("9C33-6BBD", VolumeMatch.volumeIdOfMountPath("/storage/9C33-6BBD"))
        assertEquals("9C33-6BBD", VolumeMatch.volumeIdOfMountPath("/storage/9C33-6BBD/"))
    }

    @Test
    fun sharedStorageAndOtherPathsGiveNone() {
        assertNull(VolumeMatch.volumeIdOfMountPath("/storage/emulated/0"))
        assertNull(VolumeMatch.volumeIdOfMountPath("/storage/emulated"))
        assertNull(VolumeMatch.volumeIdOfMountPath("/storage/self"))
        assertNull(VolumeMatch.volumeIdOfMountPath("/storage/9C33-6BBD/Games"))
        assertNull(VolumeMatch.volumeIdOfMountPath("/storage/"))
        assertNull(VolumeMatch.volumeIdOfMountPath("/mnt/media_rw/9C33-6BBD"))
        assertNull(VolumeMatch.volumeIdOfMountPath(""))
    }

    @Test
    fun aTreeAndAGameUnderItAreOnTheirVolume() {
        assertTrue(VolumeMatch.treeOnVolume(gamesTree, "9C33-6BBD"))
        assertTrue(VolumeMatch.treeOnVolume("$gamesTree/Final Fantasy #1 (100%).chd", "9C33-6BBD"))
        assertTrue(VolumeMatch.treeOnVolume("$gamesTree/Disc?/game.cue", "9C33-6BBD"))
    }

    @Test
    fun theVolumeIdMatchesWhateverItsCase() {
        assertTrue(VolumeMatch.treeOnVolume(gamesTree, "9c33-6bbd"))
        assertTrue(
            VolumeMatch.treeOnVolume(
                "content://com.android.externalstorage.documents/tree/9c33-6bbd%3aGames/a.chd",
                "9C33-6BBD",
            ),
        )
    }

    @Test
    fun otherVolumesAndAuthoritiesAreNot() {
        val data = "content://com.android.externalstorage.documents/tree/primary%3AGooseStation%2Fdata"
        assertFalse(VolumeMatch.treeOnVolume(data, "9C33-6BBD"))
        assertFalse(VolumeMatch.treeOnVolume(gamesTree, "1234-ABCD"))
        // A volume ID that is only the start of another's.
        assertFalse(VolumeMatch.treeOnVolume(gamesTree, "9C33"))
        assertFalse(
            VolumeMatch.treeOnVolume(
                "content://io.github.ilyalissoboi.goosestation.testdocuments/tree/9C33-6BBD%3AGames",
                "9C33-6BBD",
            ),
        )
        assertFalse(VolumeMatch.treeOnVolume("/data/user/0/games/9C33-6BBD:a.chd", "9C33-6BBD"))
        assertFalse(VolumeMatch.treeOnVolume("content://com.android.externalstorage.documents/tree/", "9C33-6BBD"))
    }
}
