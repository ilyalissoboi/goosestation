// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.mirror.DataFolderChange
import io.github.ilyalissoboi.goosestation.mirror.DataFolderChoice
import io.github.ilyalissoboi.goosestation.mirror.MirrorState
import io.github.ilyalissoboi.goosestation.storage.BiosImportResult
import io.github.ilyalissoboi.goosestation.storage.DataFolderInspection
import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.storage.MissingGrant
import io.github.ilyalissoboi.goosestation.storage.SavedFolders

/** What [FolderSettings] asks of Android: the system picker, the grants, and what a picked folder holds. */
interface FolderAccess {
    /**
     * The system picker of the activity shown: the folder picked, once its grant is held. Null if the user backs out,
     * or with no activity. Main thread only.
     */
    suspend fun pick(kind: FolderKind): String?

    /**
     * Releases the grant on [uri], unless a slot of [saved] (the folders as they are now) still holds it. Nothing with
     * no activity. A binder call: keep it off the main thread.
     */
    fun release(
        uri: String,
        saved: SavedFolders,
    )

    /** Copies the BIOS images in [treeUri] to where the core reads them, and describes what it found. */
    suspend fun importBios(treeUri: String): BiosImportResult

    /**
     * Whether [treeUri] can be the data folder alongside the [games] folders, and what it holds if it can. Folder
     * reads and a write: keep it off the main thread.
     */
    fun inspectDataFolder(
        treeUri: String,
        games: List<String>,
    ): DataFolderInspection

    /** The folders of [saved] whose grant is gone. A binder call: keep it off the main thread. */
    fun missingGrants(saved: SavedFolders): List<MissingGrant>
}

/** The data folder's move to another folder: [DataFolderChange.switchFrom], from the mirror running now. */
fun interface DataFolderSwitch {
    /**
     * Moves the working copy over to [newTree] as [choice] says, [beforeApply] run once the old mirror has closed.
     * False, with nothing changed, if the old folder missed changes that [choice] would lose and the loss isn't
     * [lossAccepted]. Throws as [DataFolderChange.apply] does.
     */
    suspend fun switch(
        newTree: String,
        choice: DataFolderChoice,
        pushOld: Boolean,
        lossAccepted: Boolean,
        beforeApply: suspend () -> Unit,
    ): Boolean
}

/** What [FolderSettings] asks the rest of the app, or tells it. Main thread only. */
interface FolderHooks {
    /** The folder settings are on screen. */
    val onFolders: Boolean

    /** The library is on screen. */
    val onLibrary: Boolean

    /**
     * No data folder change can start now: a game isn't over yet, the cold start is under way, or the data layout
     * hasn't been read from the core.
     */
    val busy: Boolean

    /** The data folder mirror's state, as it is now. */
    val mirrorState: MirrorState

    /** The BIOS images were copied again: a region set to one that's gone goes back to Automatic. */
    suspend fun biosImagesChanged()

    /** The settings page shown, if it's one that lists the BIOS images, reads them again. */
    fun reloadShownPage()

    /** The games folders changed: the core is given them, and the library scanned. */
    suspend fun updateLibrary()

    /** Opens the folder settings, in their page. */
    fun showFolders()

    /** Pulls the whole data folder. */
    suspend fun pullAll()

    /** Starts [data]'s mirror again. */
    fun useMirror(data: String)

    /** Waits out what's queued for the core. */
    suspend fun settleCore()

    /** Restarts the process. Everything to keep is saved first. */
    fun restart()
}
