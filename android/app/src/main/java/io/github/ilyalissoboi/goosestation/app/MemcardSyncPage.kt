// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.MemcardSyncState
import io.github.ilyalissoboi.goosestation.core.SyncConflict
import io.github.ilyalissoboi.goosestation.core.SyncStatusKind
import io.github.ilyalissoboi.goosestation.settings.CoreSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Memory cards page: memory card sync's settings, saved through [settings] on [core] and then applied, and its
 * secrets, kept in [secrets] on [io], never in settings.ini. What it shows is [status]'s state, read again after each
 * change. Main thread only; the page can't be opened during a game.
 */
class MemcardSyncPage(
    private val scope: CoroutineScope,
    private val core: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val api: CoreApi,
    private val settings: CoreSettings,
    private val secrets: SyncSecrets,
    private val status: MemcardSyncStatus,
) {
    /** Sync's settings and state, as last read. */
    val state: MemcardSyncState? get() = status.state

    /** The conflicts, as read on opening the conflict screen; a choice made drops its conflict at once. */
    var conflicts by mutableStateOf<List<SyncConflict>?>(null)
        private set

    /** Reads the conflicts, from none shown. */
    fun loadConflicts() {
        conflicts = null
        scope.launch { conflicts = withContext(core) { api.getMemcardSyncConflicts() } }
    }

    /**
     * Takes choice [choice] of conflict [id]: index 0 this device's version, then each other one, then deciding
     * later, which does nothing. The core writes the choice to the card once no game has it.
     */
    fun resolve(
        id: Long,
        choice: Int,
    ) {
        val conflict = conflicts?.firstOrNull { it.id == id } ?: return
        if (choice > conflict.remote.size) return
        val keepLocal = choice == 0
        val remote = if (keepLocal) 0 else choice - 1
        conflicts = conflicts?.filterNot { it.id == id }
        scope.launch {
            withContext(core) { api.resolveMemcardSyncConflict(id, keepLocal, remote) }
            status.refresh()
        }
    }

    /** Keeps this device's version of every conflict shown. */
    fun keepAllLocal() {
        val ids = conflicts.orEmpty().map { it.id }
        conflicts = emptyList()
        scope.launch {
            withContext(core) { ids.forEach { api.resolveMemcardSyncConflict(it, true, 0) } }
            status.refresh()
        }
    }

    /** Turns sync on or off. */
    fun setEnabled(on: Boolean) =
        change {
            settings.setBool(SECTION, "Enabled", on)
            true
        }

    /** Sets `RemoteURI`, `AccessKeyID` or `DeviceName` to [value], trimmed; the same value does nothing. */
    fun setText(
        key: String,
        value: String,
    ) = change {
        val trimmed = value.trim()
        if (settings.getString(SECTION, key, "") == trimmed) {
            false
        } else {
            settings.setString(SECTION, key, trimmed)
            true
        }
    }

    /**
     * Keeps [value] as the secret [name] (a [SyncSecrets] name): the secret access key trimmed, the passphrase as it is,
     * since it must be the same on every device. Blank does nothing.
     */
    fun storeSecret(
        name: String,
        value: String,
    ) {
        val kept = if (name == SyncSecrets.SECRET_ACCESS_KEY) value.trim() else value
        if (kept.isBlank()) return
        keepSecret(name, kept)
    }

    /** Deletes the passphrase from this device. */
    fun removePassphrase() = keepSecret(SyncSecrets.ENCRYPTION_PASSPHRASE, null)

    /** A full sync now. */
    fun syncNow() {
        scope.launch {
            withContext(core) { api.memcardSyncNow() }
            status.refresh()
        }
    }

    // Saves the secret in the Keystore, then records in settings.ini that there is one (as desktop does), and applies.
    private fun keepSecret(
        name: String,
        value: String?,
    ) {
        scope.launch {
            if (!withContext(io) { secrets.store(name, value) }) return@launch
            withContext(core) {
                settings.setBool(
                    SECTION,
                    if (name ==
                        SyncSecrets.SECRET_ACCESS_KEY
                    ) {
                        "SecretStored"
                    } else {
                        "PassphraseStored"
                    },
                    value != null,
                )
                api.applyMemcardSync()
            }
            status.refresh()
        }
    }

    // Runs [save] on the core, which says whether it changed anything, then applies the settings if it did.
    private fun change(save: () -> Boolean) {
        scope.launch {
            val changed =
                withContext(core) {
                    save().also { if (it) api.applyMemcardSync() }
                }
            if (changed) status.refresh()
        }
    }

    private companion object {
        const val SECTION = "MemoryCardSync"
    }
}

/** The status line memory card sync's page shows: Big Picture's sentences, with "…" for "...". */
object SyncStatusWords {
    fun line(state: MemcardSyncState): String =
        when (state.statusKind) {
            SyncStatusKind.Unavailable -> "Memory card sync is unavailable: the emusync library is missing."
            SyncStatusKind.Applying -> "Connecting to the storage…"
            SyncStatusKind.StartError -> state.startError
            SyncStatusKind.Off -> "Off."
            SyncStatusKind.Incomplete -> "Not syncing: fill in the storage, access key ID and secret access key."
            SyncStatusKind.Status -> state.status.replace("...", "…")
            SyncStatusKind.Idle -> "Syncing. Nothing has changed yet."
        }
}
