// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.MemcardSyncState
import io.github.ilyalissoboi.goosestation.core.SyncActivity
import io.github.ilyalissoboi.goosestation.core.SyncStatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncBadgesTest {
    // Sync on and running, with a finished sync: Synced, unless a test says otherwise.
    private fun state(
        statusKind: SyncStatusKind = SyncStatusKind.Status,
        enabled: Boolean = true,
        running: Boolean = true,
        applying: Boolean = false,
        hasPassphrase: Boolean = false,
        passphraseRecorded: Boolean = false,
        startError: String = "",
        activity: SyncActivity = SyncActivity.Synced,
        conflictCount: Int = 0,
    ) = MemcardSyncState(
        statusKind,
        enabled,
        running,
        applying,
        "s3://goose-saves/emusync/v1",
        "GOOSEFAKEACCESSKEY",
        "",
        "Goose Test Device",
        hasSecret = true,
        hasPassphrase = hasPassphrase,
        passphraseRecorded = passphraseRecorded,
        startError = startError,
        status = "Synced at 14:05.",
        activity = activity,
        conflictCount = conflictCount,
    )

    @Test
    fun offShowsNothing() {
        assertNull(SyncBadges.of(null))
        assertNull(SyncBadges.of(state(statusKind = SyncStatusKind.Off, enabled = false, running = false)))
    }

    @Test
    fun missingLibraryFailed() {
        assertEquals(SyncBadge.Failed, SyncBadges.of(state(statusKind = SyncStatusKind.Unavailable, running = false)))
    }

    @Test
    fun incompleteNeedsSetup() {
        assertEquals(
            SyncBadge.NeedsSetup,
            SyncBadges.of(state(statusKind = SyncStatusKind.Incomplete, running = false, activity = SyncActivity.None)),
        )
    }

    @Test
    fun rejectedKeyNeedsSetup() {
        assertEquals(
            SyncBadge.NeedsSetup,
            SyncBadges.of(
                state(
                    statusKind = SyncStatusKind.StartError,
                    running = false,
                    startError = "The storage rejected the access key ID or secret.",
                    activity = SyncActivity.AuthRequired,
                ),
            ),
        )
        assertEquals(SyncBadge.NeedsSetup, SyncBadges.of(state(activity = SyncActivity.AuthRequired)))
    }

    // A settings.ini from desktop says it has a passphrase, which this device hasn't been given.
    @Test
    fun recordedButMissingPassphraseNeedsSetup() {
        assertEquals(
            SyncBadge.NeedsSetup,
            SyncBadges.of(state(passphraseRecorded = true, hasPassphrase = false, activity = SyncActivity.Locked)),
        )
        assertEquals(SyncBadge.Synced, SyncBadges.of(state(passphraseRecorded = true, hasPassphrase = true)))
    }

    @Test
    fun applyingIsSyncing() {
        assertEquals(
            SyncBadge.Syncing,
            SyncBadges.of(state(statusKind = SyncStatusKind.Applying, applying = true, running = false)),
        )
    }

    @Test
    fun startErrorFailed() {
        assertEquals(
            SyncBadge.Failed,
            SyncBadges.of(
                state(
                    statusKind = SyncStatusKind.StartError,
                    running = false,
                    startError = "The storage address is not valid.",
                    activity = SyncActivity.Failed,
                ),
            ),
        )
        assertEquals(SyncBadge.Failed, SyncBadges.of(state(activity = SyncActivity.Failed)))
    }

    @Test
    fun lockedFailed() {
        assertEquals(SyncBadge.Failed, SyncBadges.of(state(activity = SyncActivity.Locked)))
    }

    @Test
    fun conflictsCounted() {
        assertEquals(SyncBadge.Conflicts(2), SyncBadges.of(state(conflictCount = 2)))
        assertEquals("2 conflicts", SyncBadge.Conflicts(2).word)
        assertEquals("1 conflict", SyncBadge.Conflicts(1).word)
        assertEquals(SyncBadge.Conflicts(1), SyncBadges.of(state(conflictCount = 1, activity = SyncActivity.Syncing)))
    }

    @Test
    fun runningWithNoResultIsSyncing() {
        assertEquals(
            SyncBadge.Syncing,
            SyncBadges.of(state(statusKind = SyncStatusKind.Idle, activity = SyncActivity.None)),
        )
        assertEquals(SyncBadge.Syncing, SyncBadges.of(state(activity = SyncActivity.Syncing)))
        assertEquals("Syncing…", SyncBadge.Syncing.word)
    }

    @Test
    fun offline() {
        assertEquals(SyncBadge.Offline, SyncBadges.of(state(activity = SyncActivity.Offline)))
        assertEquals("Offline", SyncBadge.Offline.word)
    }

    @Test
    fun synced() {
        assertEquals(SyncBadge.Synced, SyncBadges.of(state()))
        assertEquals("Synced", SyncBadge.Synced.word)
        assertEquals("Needs setup", SyncBadge.NeedsSetup.word)
        assertEquals("Sync failed", SyncBadge.Failed.word)
    }

    @Test
    fun neverSyncedWhenNothingSyncs() {
        for (kind in SyncStatusKind.entries) {
            for (activity in SyncActivity.entries) {
                for (applying in listOf(false, true)) {
                    val badge =
                        SyncBadges.of(
                            state(statusKind = kind, running = false, applying = applying, activity = activity),
                        )
                    assertNotEquals("$kind $activity $applying", SyncBadge.Synced, badge)
                }
            }
        }
    }
}
