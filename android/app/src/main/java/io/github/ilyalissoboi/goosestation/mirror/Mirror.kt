// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What the mirror is doing. */
sealed interface MirrorState {
    /**
     * How many pushes in a row, up to the last one that finished, reached the data folder but couldn't save every
     * working-copy change to it (a side couldn't be listed in full, or a file failed). Pulls, which save nothing, and
     * runs that couldn't reach the folder at all neither count nor end the streak.
     */
    val failedRuns: Int get() = 0

    /** Not running, and the last run reached the data folder and saved every change to it. */
    data object Idle : MirrorState

    /** Not running, and the last [failedRuns] pushes reached the data folder, but left changes the next one retries. */
    data class Incomplete(
        override val failedRuns: Int,
    ) : MirrorState

    /** A run is at action [done] of [total]; [failedRuns] is as it was before this run. */
    data class Syncing(
        val done: Int,
        val total: Int,
        override val failedRuns: Int = 0,
    ) : MirrorState

    /** The last run couldn't reach the data folder: changes stay in the working copy until a run can. */
    data class Unreachable(
        val reason: String,
    ) : MirrorState
}

/** The working-copy files a pull replaced or deleted. */
data class PullResult(
    val changed: Set<String>,
)

/**
 * The working-copy files a push replaced or deleted, where the folder's side won, and whether the run [complete]d its
 * whole plan: false when it stopped at an unreachable folder, couldn't list it, skipped a deferred file, or any file
 * failed, so some of the working copy's changes may not be in the folder.
 */
data class PushResult(
    val changed: Set<String>,
    val complete: Boolean,
)

/** What one run did. */
private class SyncResult(
    val changed: Set<String>,
    val complete: Boolean,
)

/**
 * Keeps the working copy and the data folder in step, as [MirrorPlanner] decides, remembering what it last saw of
 * both in a [Manifest] kept in [manifestFile] (outside the working copy). [clock] is the wall clock, in
 * milliseconds, that working-copy times are compared with. [layout] is what's mirrored, asked at the start of each
 * run: once the core reports it ([DataLayout.fromCore]), each run that lists both sides first deletes the working
 * copy's [DataLayout.stray] files that are in step with the folder, and their manifest entries, never touching the
 * folder.
 *
 * Runs never overlap: they take turns on one thread. A run that can't reach the folder stops, with what it had done
 * kept, and the state [MirrorState.Unreachable]; the next run tries again. A file that fails on its own is left for
 * the next run, and so is everything when the folder is reachable but either side can't be listed in full; the
 * state is then [MirrorState.Incomplete] if a working-copy change didn't reach the folder. Nothing but cancellation
 * is thrown.
 */
class Mirror(
    private val store: FolderStore,
    private val workingCopy: File,
    private val manifestFile: File,
    private val clock: () -> Long,
    private val layout: () -> DataLayout,
) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val mutableState = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = mutableState.asStateFlow()

    // Touched only on the dispatcher. Every action is idempotent, so a manifest that lags behind is only replayed.
    private var manifest: Manifest? = null
    private var unsaved = 0
    private var closed = false

    // The runs in a row that reached the folder but failed to save something to it: MirrorState.failedRuns.
    private var failedRuns = 0

    /** Folder to working copy, except the [deferred] paths. */
    suspend fun pull(deferred: (String) -> Boolean): PullResult =
        withContext(dispatcher) {
            PullResult(
                sync(deferred, saves = false) { folder, local, manifest, layout ->
                    MirrorPlanner.pull(folder, local, manifest, layout, deferred)
                }.changed,
            )
        }

    /**
     * Working copy to folder, without waiting for files to settle when [force]. A file the folder changed as well is
     * copied in instead, so the result says which working-copy files changed, as a pull's does.
     */
    suspend fun push(
        force: Boolean,
        deferred: (String) -> Boolean,
    ): PushResult =
        withContext(dispatcher) {
            val result =
                sync(deferred, saves = true) { folder, local, manifest, layout ->
                    MirrorPlanner.push(folder, local, manifest, layout, clock(), force, deferred)
                }
            PushResult(result.changed, result.complete)
        }

    /**
     * Ends the mirror, for a data folder change: the run under way finishes first, and every later run does nothing,
     * so nothing it does can touch the working copy or the manifest file once this returns.
     */
    suspend fun close() = withContext(dispatcher) { closed = true }

    /**
     * One run of the actions [plan] gives; returns the working-copy paths it replaced or deleted, and whether it did
     * every action ([SyncResult.complete]); a closed mirror's run is incomplete, as it does nothing. [deferred] is
     * asked again before each working-copy replace or delete, so a path deferred since the plan was made (a game
     * started) is left for a later run. A run that [saves] (a push's) counts towards [MirrorState.failedRuns]; a
     * pull's leaves the count as it is, as it saves nothing.
     */
    private suspend fun sync(
        deferred: (String) -> Boolean,
        saves: Boolean,
        plan: (Map<String, FileState>, Map<String, FileState>, Manifest, DataLayout) -> List<MirrorAction>,
    ): SyncResult {
        // A run never suspends, so on the one-thread dispatcher it can't overlap close().
        if (closed) return SyncResult(emptySet(), complete = false)
        val layout = layout()
        // The listing recovers interrupted replaces first, so a file only a .goosebak holds isn't seen as deleted.
        val listings =
            try {
                store.listAll(layout) to LocalTree.list(workingCopy)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FolderUnreachableException) {
                unreachable(e)
                return SyncResult(emptySet(), complete = false)
            } catch (e: Exception) {
                // Reachable, but a side not listed in full (a provider still loading a folder, say, or a working-copy
                // folder that can't be read): acting on a partial listing would delete files, so nothing is done.
                Log.w(TAG, "Can't list the data folder or the working copy; the next run tries again.", e)
                finish(saves, failed = true)
                return SyncResult(emptySet(), complete = false)
            }
        val (folder, local) = listings

        val changed = HashSet<String>()
        var complete = true
        // Whether a working-copy change didn't reach the folder.
        var failed = false
        try {
            if (layout.fromCore) removeStrays(local, layout)
            val actions = plan(folder, local, loadManifest(), layout)
            for ((done, action) in actions.withIndex()) {
                currentCoroutineContext().ensureActive()
                mutableState.value = MirrorState.Syncing(done, actions.size, failedRuns)
                // Its manifest entry is unchanged, so a later run plans it again. This narrows the race with a game
                // starting, but can't close it: one may start while this action runs.
                if ((action is CopyToLocal || action is DeleteLocal) && deferred(action.path)) {
                    complete = false
                    continue
                }
                try {
                    record(perform(action, folder, loadManifest()))
                    if (action is CopyToLocal || action is DeleteLocal) changed += action.path
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FolderUnreachableException) {
                    unreachable(e)
                    return SyncResult(changed, complete = false)
                } catch (e: Exception) {
                    // This one file: its manifest entry is unchanged, so the next run plans it again.
                    Log.w(TAG, "$action failed; the next run tries again.", e)
                    complete = false
                    if (action is CopyToFolder || action is DeleteFromFolder) failed = true
                }
            }
            finish(saves, failed)
        } finally {
            save()
            if (mutableState.value is MirrorState.Syncing) mutableState.value = idle()
        }
        return SyncResult(changed, complete)
    }

    /**
     * Deletes the working copy's [DataLayout.stray] files among [local] that are in step with the data folder (as
     * the manifest last saw them, and unchanged since), and the folders they leave empty, and drops their manifest
     * entries. The data folder keeps its copies. Any other stray (changed since, or never mirrored: a folder
     * settings.ini no longer names may hold a change that never reached the data folder) stays, with its entry. The
     * manifest's other entries outside the [layout] go when their file is gone from the working copy, or is one that
     * is never mirrored (the cache, the log, a temporary file). A file that can't be deleted keeps its entry and is
     * tried again at the next run: outside the layout, the planner never acts on it.
     */
    private fun removeStrays(
        local: Map<String, FileState>,
        layout: DataLayout,
    ) {
        val manifest = loadManifest()
        val removed = HashSet<String>()
        for ((path, state) in local) {
            if (!layout.stray(path)) continue
            val file = File(workingCopy, path)
            if (manifest[path]?.local != state) {
                Log.w(TAG, "Keeping $file: it isn't GooseStation's data, but may hold a change the folder lacks.")
                continue
            }
            if (!file.delete()) {
                Log.w(TAG, "Can't delete $file, which isn't GooseStation's data.")
                continue
            }
            removed += path
            // Its folders, up to the working copy, while they're empty (delete() fails on one that isn't).
            var parent = file.parentFile
            while (parent != null && parent != workingCopy && parent.delete()) parent = parent.parentFile
        }
        val kept =
            manifest.entries.filterKeys { path ->
                layout.mirrored(path) || (path in local && path !in removed && layout.stray(path))
            }
        if (kept.size != manifest.entries.size) record(Manifest(kept))
    }

    /** Ends a run that reached the folder, one that [saves], [failed] if a working-copy change didn't reach it. */
    private fun finish(
        saves: Boolean,
        failed: Boolean,
    ) {
        if (saves) failedRuns = if (failed) failedRuns + 1 else 0
        mutableState.value = idle()
    }

    private fun idle() = if (failedRuns > 0) MirrorState.Incomplete(failedRuns) else MirrorState.Idle

    /**
     * Carries out [action], and returns the manifest with the states it saw. A copy records the source's state as
     * it was before the copy read it, so a write during the copy still counts as a change.
     */
    private fun perform(
        action: MirrorAction,
        folder: Map<String, FileState>,
        manifest: Manifest,
    ): Manifest {
        val path = action.path
        val file = File(workingCopy, path)
        return when (action) {
            is CopyToLocal -> {
                store.copyFrom(path, file)
                val local = stateOf(file) ?: throw IOException("$file is gone after the copy")
                manifest.with(path, ManifestEntry(local, folder.getValue(path)))
            }
            is CopyToFolder -> {
                // Gone since the listing: the next push deletes it from the folder.
                val local = stateOf(file) ?: return manifest
                manifest.with(path, ManifestEntry(local, store.replace(path, file, folder[path])))
            }
            is DeleteLocal -> {
                if (!file.delete() && file.exists()) throw IOException("Can't delete $file")
                manifest.without(path)
            }
            is DeleteFromFolder -> {
                // The planner deletes only a file the listing holds; one that changed since is kept.
                folder[path]?.let { store.delete(path, it) }
                manifest.without(path)
            }
            is Forget -> manifest.without(path)
        }
    }

    private fun stateOf(file: File) = if (file.isFile) FileState(file.length(), file.lastModified()) else null

    private fun unreachable(e: IOException) {
        Log.w(TAG, "The data folder can't be reached.", e)
        mutableState.value = MirrorState.Unreachable(e.message ?: e.javaClass.name)
    }

    private fun loadManifest(): Manifest =
        manifest ?: readManifest().also { manifest = it }

    private fun readManifest(): Manifest {
        if (!manifestFile.exists()) return Manifest(emptyMap())
        val text =
            try {
                manifestFile.readText()
            } catch (e: IOException) {
                Log.w(TAG, "Can't read $manifestFile", e)
                null
            }
        return text?.let(Manifest::decode)
            ?: Manifest(emptyMap()).also { Log.w(TAG, "$manifestFile is unreadable: starting with an empty one.") }
    }

    /** Takes [changed] as the manifest, saving it every [SAVE_EVERY] changes. */
    private fun record(changed: Manifest) {
        if (changed === manifest) return
        manifest = changed
        if (++unsaved >= SAVE_EVERY) save()
    }

    /** Writes the manifest, if it changed, whole or not at all. A failure is retried at the next save. */
    private fun save() {
        val current = manifest ?: return
        if (unsaved == 0) return
        try {
            LocalTree.copyIn(current.encode().byteInputStream(), manifestFile)
            unsaved = 0
        } catch (e: IOException) {
            Log.w(TAG, "Can't write $manifestFile", e)
        }
    }

    private companion object {
        const val TAG = "Mirror"
        const val SAVE_EVERY = 50
    }
}
