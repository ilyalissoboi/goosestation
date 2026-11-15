// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.mirror.DataFolderChoice
import io.github.ilyalissoboi.goosestation.mirror.MirrorState
import io.github.ilyalissoboi.goosestation.storage.BiosImportResult
import io.github.ilyalissoboi.goosestation.storage.DataFolderCheck
import io.github.ilyalissoboi.goosestation.storage.DataFolderInspection
import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.storage.FolderPrefs
import io.github.ilyalissoboi.goosestation.storage.MissingGrant
import io.github.ilyalissoboi.goosestation.storage.SavedFolders
import io.github.ilyalissoboi.goosestation.ui.DataFolderCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The saved folders (the BIOS folder, the games folders and the data folder), their grants, and what changes them:
 * which folder a pick replaces, what is saved, released and refreshed after it, and the data folder change, which
 * ends in a restart. Main thread only, every field and function: the preferences are written on [prefsDispatcher],
 * and the grants and folder reads run on [io].
 */
class FolderSettings(
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    /** One at a time: saves in the order they're made, so an older one never lands last. */
    private val prefsDispatcher: CoroutineContext,
    private val prefs: FolderPrefs,
    private val access: FolderAccess,
    private val dataSwitch: DataFolderSwitch,
    private val hooks: FolderHooks,
    private val status: StatusLine,
    /** A line for the log, at an [android.util.Log] priority, with what was thrown if anything was. */
    private val log: (priority: Int, line: String, error: Throwable?) -> Unit,
) {
    /** The saved folders, as last loaded or saved. The UI reads it. */
    var folders by mutableStateOf(SavedFolders(bios = null, games = emptyList(), data = null, setupComplete = false))
        private set

    /** The BIOS images the last import copied, as it described them. The UI reads it. */
    var biosImages by mutableStateOf(emptyList<String>())
        private set

    /** Saved folders whose grant is gone, as the last check found. The UI reads it. */
    var missingGrants by mutableStateOf(emptyList<MissingGrant>())
        private set

    /**
     * A folder picked to become the data folder, waiting for the user's choice in the folder settings. The UI reads
     * it.
     */
    var dataCandidate by mutableStateOf<DataFolderCandidate?>(null)
        private set

    /**
     * The last try at changing to [dataCandidate] found changes that didn't reach the old data folder: the choice is
     * shown again, warning that using the new folder's data loses them.
     */
    private var oldFolderMissedChanges by mutableStateOf(false)

    /** What a data folder change under way is doing; null when none is. It ends in a restart. The UI reads it. */
    var dataChange by mutableStateOf<String?>(null)
        private set

    private var biosJob: Job? = null

    /** The data folder's grant is held, or there's no data folder. */
    val dataGranted get() = missingGrants.none { it.kind == FolderKind.Data }

    /** The games folders the app can read: one without its grant is left out until it's picked again. */
    fun readableGameFolders(): Array<String> {
        val unreadable = missingGrants.filter { it.kind == FolderKind.Games }.map { it.uri }.toSet()
        return folders.games.filterNot { it in unreadable }.toTypedArray()
    }

    // --- Loading and saving ---

    /** Reads the saved folders, which it returns for the cold start to [use], and the BIOS images last imported. */
    suspend fun load(): SavedFolders {
        val (saved, images) = withContext(io) { prefs.load() to prefs.biosImages() }
        biosImages = images
        return saved
    }

    /** [saved] are the folders from now on: the ones loaded, or the ones setup chose. Nothing is saved. */
    fun use(saved: SavedFolders) {
        folders = saved
    }

    /** Saves [folders], committed before it returns; false, with the error shown, if that failed. */
    suspend fun save(): Boolean {
        val saved = folders
        val written = withContext(prefsDispatcher) { prefs.save(saved) }
        if (!written) status.error("Can't save your folders.")
        return written
    }

    /** Looks again for saved folders whose grant is gone ([missingGrants]). */
    suspend fun checkGrants() {
        val saved = folders
        missingGrants = withContext(io) { access.missingGrants(saved) }
    }

    private suspend fun release(uri: String) {
        val saved = folders
        withContext(io) { access.release(uri, saved) }
    }

    // --- Setup ---

    /**
     * Copies the BIOS images in [treeUri], and remembers what it found, if anything. A region whose image isn't among
     * the new copies goes back to Automatic ([FolderHooks.biosImagesChanged]).
     */
    suspend fun importBios(treeUri: String): BiosImportResult {
        val result = access.importBios(treeUri)
        if (result.images.isNotEmpty()) {
            biosImages = result.images
            withContext(prefsDispatcher) { prefs.saveBiosImages(result.images) }
            hooks.biosImagesChanged()
        }
        return result
    }

    /** Whether [treeUri] can be the data folder alongside the [games] folders, and what it holds if it can. */
    suspend fun inspectDataFolder(
        treeUri: String,
        games: List<String>,
    ): DataFolderInspection = withContext(io) { access.inspectDataFolder(treeUri, games) }

    /** Lets go of a folder setup picked and then dropped, unless a saved folder still uses it. */
    fun releaseFolder(uri: String) {
        val saved = folders
        scope.launch(io) { access.release(uri, saved) }
    }

    // --- Missing grants ---

    /**
     * Picks [grant]'s folder again. The same folder restores it. Another one replaces a games or BIOS folder (the BIOS
     * is imported again); for the data folder, the folder settings open on the choice of what to do with it.
     */
    fun repick(grant: MissingGrant) {
        scope.launch {
            val picked = access.pick(grant.kind) ?: return@launch
            if (picked != grant.uri) {
                when (grant.kind) {
                    FolderKind.Data -> {
                        offerDataFolder(picked)
                        if (dataCandidate?.uri == picked && hooks.onLibrary) hooks.showFolders()
                        return@launch
                    }
                    FolderKind.Bios -> {
                        val result = importBios(picked)
                        if (result.images.isEmpty()) {
                            release(picked)
                            status.error(result.error ?: "No PS1 BIOS found in this folder. Pick another.")
                            return@launch
                        }
                        folders = folders.copy(bios = picked)
                    }
                    FolderKind.Games -> {
                        if (refuseGamesFolder(picked)) return@launch
                        val games = folders.games.map { if (it == grant.uri) picked else it }.distinct()
                        folders = folders.copy(games = games)
                    }
                }
                save()
                release(grant.uri)
            }
            checkGrants()
            when (grant.kind) {
                FolderKind.Data -> if (dataGranted) hooks.pullAll()
                FolderKind.Games -> hooks.updateLibrary()
                FolderKind.Bios -> {}
            }
        }
    }

    /** Stops listing a games folder whose grant is gone, and releases it. */
    fun removeGamesFolder(uri: String) = setGamesFolders(folders.games - uri)

    // --- The folder settings ---

    /** Picks another BIOS folder, which replaces the old one if it holds any images. */
    fun changeBios() {
        if (biosJob?.isActive == true) return
        biosJob =
            scope.launch {
                val picked = access.pick(FolderKind.Bios) ?: return@launch
                useBiosFolder(picked, rescan = false)
            }
    }

    /** Imports the BIOS folder's images again. */
    fun rescanBios() {
        val bios = folders.bios ?: return
        if (biosJob?.isActive == true) return
        biosJob = scope.launch { useBiosFolder(bios, rescan = true) }
    }

    fun addGamesFolder() {
        scope.launch {
            val picked = access.pick(FolderKind.Games) ?: return@launch
            if (refuseGamesFolder(picked)) return@launch
            if (picked !in folders.games) setGamesFolders(folders.games + picked)
        }
    }

    /** Refuses, saying why, and lets go of, a games folder [picked] that overlaps the data folder. */
    private suspend fun refuseGamesFolder(picked: String): Boolean {
        val data = folders.data ?: return false
        if (!DataFolderCheck.overlaps(picked, data)) return false
        release(picked)
        status.error(DataFolderCheck.GAMES_OVERLAP_DATA)
        return true
    }

    /** Saves [games] as the games folders, releases the removed ones, and refreshes the library. */
    fun setGamesFolders(games: List<String>) {
        if (dataChange != null) return
        val removed = folders.games - games.toSet()
        // Now, so a second change made before this one is saved starts from it.
        folders = folders.copy(games = games.distinct())
        scope.launch {
            save()
            removed.forEach { release(it) }
            checkGrants()
            hooks.updateLibrary()
        }
    }

    /** Picks a new data folder, then waits for the user's choice of what to do with it ([changeDataFolder]). */
    fun pickDataFolder() {
        if (dataChange != null) return
        scope.launch {
            val picked = access.pick(FolderKind.Data) ?: return@launch
            offerDataFolder(picked)
        }
    }

    /** Drops the data folder picked, and its grant unless a saved folder holds it. */
    fun cancelDataChange() {
        val candidate = dataCandidate ?: return
        if (dataChange != null) return
        dataCandidate = null
        oldFolderMissedChanges = false
        scope.launch { release(candidate.uri) }
    }

    /**
     * Makes the picked data folder the data folder, as [choice] says, then restarts the process: the core's data root
     * can't change while it runs. The old folder gets this device's latest changes first, if it can be reached, and
     * its mirror then stops for good ([DataFolderSwitch]). Using the new folder's data waits for the user again if
     * some changes didn't reach the old folder, unless the choice was already shown with the warning that they're
     * lost. If the change fails, nothing is saved, and the old folder carries on.
     */
    fun changeDataFolder(choice: DataFolderChoice) {
        val candidate = dataCandidate ?: return
        val usable = candidate.inspection is DataFolderInspection.Usable
        if (!usable || !hooks.onFolders || dataChange != null) return
        // A game left a moment ago may still be stopping, and the start may still be pulling the old folder. Until the
        // core has started, the layout is only the defaults: a push to the old folder would leave out the folders its
        // settings.ini names, and still count as complete.
        if (hooks.busy) {
            status.show(Status.Note, "GooseStation is busy. Try again in a moment.")
            return
        }
        val old = folders.data
        // What the screen said when the user chose: with the old folder unreachable, that its missing changes are lost.
        val lossAccepted = !dataReachable(hooks.mirrorState)
        dataChange = "Saving your latest changes to the old data folder…"
        scope.launch {
            val failure =
                try {
                    val applied =
                        dataSwitch.switch(
                            newTree = candidate.uri,
                            choice = choice,
                            pushOld = dataGranted,
                            lossAccepted = lossAccepted,
                        ) {
                            dataChange =
                                when (choice) {
                                    DataFolderChoice.CopyThisDevicesData ->
                                        "Copying this device's data into the new folder…"
                                    DataFolderChoice.UseFolderData -> "Copying the data from the new folder…"
                                }
                            // A settings reload or folder change queued before the mirror closed would have the core
                            // save its settings.ini over the one the change brings in.
                            hooks.settleCore()
                        }
                    if (!applied) {
                        oldFolderMissedChanges = true
                        dataChange = null
                        status.error(
                            "Not every change on this device reached your old data folder. Choose again to use the " +
                                "new folder's data anyway, or cancel.",
                        )
                        return@launch
                    }
                    folders = folders.copy(data = candidate.uri)
                    if (save()) null else "can't save your folders"
                } catch (e: Exception) {
                    log(Log.ERROR, "Can't change the data folder.", e)
                    e.message ?: e.javaClass.name
                }
            if (failure != null) {
                abandonDataChange(old, candidate.uri, failure)
                return@launch
            }
            old?.let { release(it) }
            dataChange = "Restarting…"
            log(Log.INFO, "The data folder changed: restarting.", null)
            hooks.restart()
        }
    }

    /** The data folder stays [old]: its mirror carries on, and the folder picked ([uri]) is let go of. */
    private suspend fun abandonDataChange(
        old: String?,
        uri: String,
        error: String,
    ) {
        if (folders.data != old) {
            folders = folders.copy(data = old)
            save()
        }
        dataCandidate = null
        oldFolderMissedChanges = false
        dataChange = null
        release(uri)
        status.error("Can't change the data folder ($error). The old one is still your data folder.")
        old ?: return
        hooks.useMirror(old)
        // The working copy may be part-way changed: a full pull makes it match the old folder again, and reloads
        // settings.ini if it came back.
        hooks.pullAll()
    }

    /**
     * Offers [uri] as the new data folder, once it's looked at, unless it's the data folder already. One that can't be
     * the data folder ([DataFolderCheck]) is offered only to say why.
     */
    private suspend fun offerDataFolder(uri: String) {
        if (uri == folders.data) {
            status.show(Status.Note, "That's your data folder already.")
            return
        }
        status.show(Status.LookingAtFolder, "Looking at the folder…")
        val inspection = inspectDataFolder(uri, folders.games)
        if (dataChange != null) return
        dataCandidate?.let { if (it.uri != uri) release(it.uri) }
        oldFolderMissedChanges = false
        dataCandidate = DataFolderCandidate(uri, inspection)
        status.clear(Status.LookingAtFolder)
    }

    /** The data folder holds the latest changes, as far as the app knows. */
    fun dataReachable(state: MirrorState): Boolean =
        dataGranted && state !is MirrorState.Unreachable && !oldFolderMissedChanges

    /** Imports [uri]'s BIOS images, and makes it the BIOS folder if it has any; otherwise nothing changes. */
    private suspend fun useBiosFolder(
        uri: String,
        rescan: Boolean,
    ) {
        status.show(Status.Note, "Looking for BIOS images…")
        val result = importBios(uri)
        if (result.images.isEmpty()) {
            if (uri != folders.bios) release(uri)
            val error = result.error ?: "No PS1 BIOS found in this folder."
            status.error(if (rescan) "$error The copies already made still work." else "$error Pick another.")
            return
        }
        status.clear()
        // The BIOS page, if it was opened meanwhile, shows the images just copied.
        hooks.reloadShownPage()
        val old = folders.bios
        if (uri == old) return
        folders = folders.copy(bios = uri)
        save()
        old?.let { release(it) }
        checkGrants()
    }
}
