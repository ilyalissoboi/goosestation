// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.MemcardSyncState
import io.github.ilyalissoboi.goosestation.core.SyncActivity
import io.github.ilyalissoboi.goosestation.core.SyncStatusKind

/** What the library's sync badge says: a word, which the badge shows with its status's icon and colours. */
sealed interface SyncBadge {
    val word: String

    /** The last sync finished. */
    data object Synced : SyncBadge {
        override val word = "Synced"
    }

    /** Settings are being applied, a sync is under way, or sync runs with nothing finished yet. */
    data object Syncing : SyncBadge {
        override val word = "Syncing…"
    }

    /** The storage couldn't be reached: saves stay here and sync once it can be. */
    data object Offline : SyncBadge {
        override val word = "Offline"
    }

    /** Saves changed both here and on another device, for the player to choose. */
    data class Conflicts(
        val count: Int,
    ) : SyncBadge {
        override val word = if (count == 1) "1 conflict" else "$count conflicts"
    }

    /** On, but something only the player can give is missing or refused here: a secret, a passphrase, a key. */
    data object NeedsSetup : SyncBadge {
        override val word = "Needs setup"
    }

    /** The library is missing, the start failed, the storage is locked, or the last sync failed. */
    data object Failed : SyncBadge {
        override val word = "Sync failed"
    }
}

/** Which badge memory card sync's state gets: none while it's off, and never Synced when nothing syncs. */
object SyncBadges {
    fun of(state: MemcardSyncState?): SyncBadge? {
        if (state == null || !state.enabled) return null
        return when {
            state.statusKind == SyncStatusKind.Unavailable -> SyncBadge.Failed
            state.statusKind == SyncStatusKind.Incomplete || state.activity == SyncActivity.AuthRequired ||
                (state.passphraseRecorded && !state.hasPassphrase) -> SyncBadge.NeedsSetup
            state.applying -> SyncBadge.Syncing
            state.statusKind == SyncStatusKind.StartError || state.activity == SyncActivity.Failed ||
                state.activity == SyncActivity.Locked -> SyncBadge.Failed
            state.conflictCount > 0 -> SyncBadge.Conflicts(state.conflictCount)
            !state.running -> SyncBadge.Syncing
            state.activity == SyncActivity.Offline -> SyncBadge.Offline
            state.activity == SyncActivity.Synced -> SyncBadge.Synced
            else -> SyncBadge.Syncing
        }
    }
}
