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

/**
 * One device's version of a save in a conflict: the [device] that made it, when ([modifiedMs], Unix ms; not
 * [timeKnown] for a time that can't be real), how many [blocks] it takes on a card, or that it [deleted] the save.
 */
data class SyncVersion(
    val device: String,
    val modifiedMs: Long,
    val timeKnown: Boolean,
    val blocks: Int,
    val deleted: Boolean,
)

/**
 * A save changed both on this device and on another since they last synced ([NativeLibrary.getMemcardSyncConflicts]):
 * its [name] in a list (the game's title, if known, then the save and its card), this device's version ([local], null
 * if the save isn't on its card) and the others' ([remote]). Its choices are this device's version, each remote one,
 * then deciding later; [defaultChoice] is the newest's index among them.
 */
data class SyncConflict(
    val id: Long,
    val name: String,
    val local: SyncVersion?,
    val remote: List<SyncVersion>,
    val defaultChoice: Int,
) {
    // For jni_memcard_sync.cpp, which has an array to give.
    constructor(id: Long, name: String, local: SyncVersion?, remote: Array<SyncVersion>, defaultChoice: Int) :
        this(id, name, local, remote.toList(), defaultChoice)
}
