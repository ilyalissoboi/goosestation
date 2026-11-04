// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/** Which status line memory card sync's page shows, most important first: the core's `MemcardSync::StatusKind`. */
enum class SyncStatusKind {
    /** The emusync library is missing. */
    Unavailable,

    /** Settings are being applied. */
    Applying,

    /** The last start failed: [MemcardSyncState.startError]. */
    StartError,

    /** Switched off. */
    Off,

    /** Switched on, but not running: settings are missing. */
    Incomplete,

    /** Running: [MemcardSyncState.status]. */
    Status,

    /** Running, with no status yet. */
    Idle,
}

/** What sync last did: the core's `MemcardSync::Activity`. */
enum class SyncActivity {
    /** Not running, or nothing reported yet. */
    None,

    /** A sync, or a start, is under way. */
    Syncing,

    /** The last sync finished. */
    Synced,

    /** The storage couldn't be reached. */
    Offline,

    /** The last sync, or the start, failed. */
    Failed,

    /** The storage is encrypted and this device has no key for it, or the passphrase is wrong. */
    Locked,

    /** The storage rejected the access key. */
    AuthRequired,
}

/**
 * Memory card sync as it is now ([NativeLibrary.getMemcardSyncState]): the settings ([MemcardSyncState.remoteUri],
 * [accessKeyId], [deviceName], with [defaultDeviceName] used for an empty one), whether the Keystore has the secrets
 * ([hasSecret], [hasPassphrase]; [passphraseRecorded] is what settings.ini says, which a file from desktop sets), and
 * what sync is doing.
 */
data class MemcardSyncState(
    val statusKind: SyncStatusKind,
    val enabled: Boolean,
    val running: Boolean,
    val applying: Boolean,
    val remoteUri: String,
    val accessKeyId: String,
    val deviceName: String,
    val defaultDeviceName: String,
    val hasSecret: Boolean,
    val hasPassphrase: Boolean,
    val passphraseRecorded: Boolean,
    val startError: String,
    val status: String,
    val activity: SyncActivity,
    val conflictCount: Int,
)
