// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.SyncConflict
import io.github.ilyalissoboi.goosestation.core.SyncVersion
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class SyncConflictWordsTest {
    private val zone = ZoneOffset.UTC
    private val at = LocalDateTime.of(2026, 11, 4, 14, 5).toInstant(zone).toEpochMilli()

    private fun version(
        device: String = "AYN Thor",
        blocks: Int = 3,
        deleted: Boolean = false,
        timeKnown: Boolean = true,
    ) = SyncVersion(device, at, timeKnown, blocks, deleted)

    @Test
    fun introSingularAndPlural() {
        assertEquals(
            "1 save changed both on this device and on another. Choose which version to keep.",
            SyncConflictWords.intro(1),
        )
        assertEquals(
            "2 saves changed both on this device and on another. Choose which version to keep.",
            SyncConflictWords.intro(2),
        )
    }

    @Test
    fun versionWithBlocks() {
        assertEquals("AYN Thor, 2026-11-04 14:05, 3 blocks", SyncConflictWords.version(version(), zone))
    }

    @Test
    fun oneBlock() {
        assertEquals("AYN Thor, 2026-11-04 14:05, 1 block", SyncConflictWords.version(version(blocks = 1), zone))
    }

    @Test
    fun deletedVersion() {
        assertEquals("AYN Thor, deleted 2026-11-04 14:05", SyncConflictWords.version(version(deleted = true), zone))
    }

    @Test
    fun unknownTime() {
        assertEquals("AYN Thor, ?, 3 blocks", SyncConflictWords.version(version(timeKnown = false), zone))
    }

    @Test
    fun summaryWithoutLocal() {
        val conflict = SyncConflict(1, "save", null, listOf(version(device = "Desktop PC")), 1)
        assertEquals(
            "This device: not on this device; other: Desktop PC, 2026-11-04 14:05, 3 blocks",
            SyncConflictWords.summary(conflict, zone),
        )
    }

    @Test
    fun summaryJoinsRemotes() {
        val conflict =
            SyncConflict(
                1,
                "save",
                version(blocks = 1),
                listOf(version(device = "Desktop PC"), version(device = "Laptop", deleted = true)),
                0,
            )
        assertEquals(
            "This device: AYN Thor, 2026-11-04 14:05, 1 block; other: Desktop PC, 2026-11-04 14:05, 3 blocks, " +
                "Laptop, deleted 2026-11-04 14:05",
            SyncConflictWords.summary(conflict, zone),
        )
    }

    @Test
    fun choicesNameEachDevice() {
        val conflict =
            SyncConflict(1, "save", version(), listOf(version(device = "Desktop PC"), version(device = "Laptop")), 1)
        assertEquals(
            listOf("This device's", "Desktop PC's", "Laptop's", "Decide later"),
            SyncConflictWords.choices(conflict),
        )
    }
}
