// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What a data folder change does with the data on each side. */
enum class DataFolderChoice {
    /** The working copy's files go into the new folder, over its files of the same name. */
    CopyThisDevicesData,

    /** The working copy is replaced by the new folder's files. */
    UseFolderData,
}

/**
 * Moves the [workingCopy] and the mirror's [manifestFile] over to a new data folder. The old folder's mirror must be
 * closed first ([Mirror.close]; [switchFrom] does it all), and the core's data root can't move while it runs: once
 * [apply] returns, the new folder is saved and the process restarted.
 */
class DataFolderChange(
    private val workingCopy: File,
    private val manifestFile: File,
) {
    /**
     * The change from the [old] folder's mirror (null if there's none), up to the save and the restart: a forced push
     * to the old folder when [pushOld] (its grant is held), then the old mirror closed, [beforeApply], and [apply].
     *
     * [DataFolderChoice.UseFolderData] deletes the working copy, so an incomplete push (or none, without the grant)
     * stops it unless [lossAccepted], the user having been told that changes not in the old folder are lost: it then
     * returns false, with the old mirror still running and nothing changed. [DataFolderChoice.CopyThisDevicesData]
     * keeps the working copy, so it goes on, logging the incomplete push. Returns true once applied; throws as
     * [apply] does, with the old mirror closed.
     */
    suspend fun switchFrom(
        old: Mirror?,
        pushOld: Boolean,
        lossAccepted: Boolean,
        newTree: String,
        choice: DataFolderChoice,
        store: FolderStore,
        beforeApply: suspend () -> Unit = {},
    ): Boolean {
        if (old != null) {
            // Called with no game running, so nothing is held back.
            val complete = pushOld && old.push(force = true) { false }.complete
            if (!complete) {
                if (choice == DataFolderChoice.UseFolderData && !lossAccepted) {
                    Log.w(TAG, "Not every change reached the old data folder: the change waits for the user.")
                    return false
                }
                Log.w(TAG, "Not every change reached the old data folder; changing it anyway ($choice).")
            }
            old.close()
        }
        beforeApply()
        apply(newTree, choice, store)
        return true
    }

    /**
     * Makes [newTree], reached through [store], the folder the working copy mirrors, as [choice] says.
     *
     * - [DataFolderChoice.CopyThisDevicesData]: every working-copy file that isn't [MirrorPlanner.excluded] is
     *   replaced into the folder; the folder's other files are left alone, and come in at the next pull. The manifest
     *   is rebuilt from both sides' states. Throws [IOException] if any file fails, before the manifest changes: the
     *   working copy is untouched, and the old folder can carry on.
     * - [DataFolderChoice.UseFolderData]: the working copy's contents, except `cache/`, are deleted, the manifest is
     *   emptied, and the folder is pulled in full. Throws [IOException] if the folder can't be listed, before anything
     *   is deleted, or if a deletion fails, with the manifest already empty, so the old folder's mirror reads the
     *   working copy's remaining files as new rather than deleted. A pull that fails is left to the next one.
     */
    suspend fun apply(
        newTree: String,
        choice: DataFolderChoice,
        store: FolderStore,
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Changing the data folder to $newTree: $choice")
        when (choice) {
            DataFolderChoice.CopyThisDevicesData -> copyThisDevicesData(store)
            DataFolderChoice.UseFolderData -> useFolderData(store)
        }
    }

    private fun copyThisDevicesData(store: FolderStore) {
        val folder = store.listAll()
        var manifest = Manifest(emptyMap())
        for ((path, local) in LocalTree.list(workingCopy).toSortedMap()) {
            if (MirrorPlanner.excluded(path)) continue
            // The state from before the copy read the file, as the mirror records it: a write since is a change.
            val written = store.replace(path, File(workingCopy, path), folder[path])
            manifest = manifest.with(path, ManifestEntry(local, written))
        }
        writeManifest(manifest)
    }

    private suspend fun useFolderData(store: FolderStore) {
        // Only a folder that can be read replaces this device's data.
        store.listAll()
        writeManifest(Manifest(emptyMap()))
        for (child in workingCopy.listFiles().orEmpty()) {
            if (child.name == CACHE) continue
            if (!child.deleteRecursively()) throw IOException("Can't delete $child")
        }
        Mirror(store, workingCopy, manifestFile, System::currentTimeMillis).pull { false }
    }

    private fun writeManifest(manifest: Manifest) = LocalTree.copyIn(manifest.encode().byteInputStream(), manifestFile)

    private companion object {
        const val TAG = "DataFolderChange"
        const val CACHE = "cache"
    }
}
