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
    /** Not running, and the last run reached the data folder. */
    data object Idle : MirrorState

    /** A run is at action [done] of [total]. */
    data class Syncing(
        val done: Int,
        val total: Int,
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
 * Keeps the working copy and the data folder in step, as [MirrorPlanner] decides, remembering what it last saw of
 * both in a [Manifest] kept in [manifestFile] (outside the working copy). [clock] is the wall clock, in
 * milliseconds, that working-copy times are compared with.
 *
 * Runs never overlap: they take turns on one thread. A run that can't reach the folder stops, with what it had done
 * kept, and the state [MirrorState.Unreachable]; the next run tries again. A file that fails on its own is left for
 * the next run, and so is everything when the folder is reachable but can't be listed in full. Nothing but
 * cancellation is thrown.
 */
class Mirror(
    private val store: FolderStore,
    private val workingCopy: File,
    private val manifestFile: File,
    private val clock: () -> Long,
) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val mutableState = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = mutableState.asStateFlow()

    // Touched only on the dispatcher. Every action is idempotent, so a manifest that lags behind is only replayed.
    private var manifest: Manifest? = null
    private var unsaved = 0

    /** Folder to working copy, except the [deferred] paths. */
    suspend fun pull(deferred: (String) -> Boolean): PullResult =
        withContext(dispatcher) {
            PullResult(sync { folder, local, manifest -> MirrorPlanner.pull(folder, local, manifest, deferred) })
        }

    /** Working copy to folder, without waiting for files to settle when [force]. */
    suspend fun push(
        force: Boolean,
        deferred: (String) -> Boolean,
    ) {
        withContext(dispatcher) {
            sync { folder, local, manifest -> MirrorPlanner.push(folder, local, manifest, clock(), force, deferred) }
        }
    }

    /** One run of the actions [plan] gives; returns the working-copy paths it replaced or deleted. */
    private suspend fun sync(
        plan: (Map<String, FileState>, Map<String, FileState>, Manifest) -> List<MirrorAction>,
    ): Set<String> {
        // The listing recovers interrupted replaces first, so a file only a .goosebak holds isn't seen as deleted.
        val folder =
            try {
                store.listAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FolderUnreachableException) {
                unreachable(e)
                return emptySet()
            } catch (e: Exception) {
                // Reachable, but not listed in full (a provider still loading a folder, say): acting on a partial
                // listing would delete files, so nothing is done.
                Log.w(TAG, "Can't list the data folder; the next run tries again.", e)
                mutableState.value = MirrorState.Idle
                return emptySet()
            }
        val actions = plan(folder, LocalTree.list(workingCopy), loadManifest())

        val changed = HashSet<String>()
        try {
            for ((done, action) in actions.withIndex()) {
                currentCoroutineContext().ensureActive()
                mutableState.value = MirrorState.Syncing(done, actions.size)
                try {
                    record(perform(action, folder, loadManifest()))
                    if (action is CopyToLocal || action is DeleteLocal) changed += action.path
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FolderUnreachableException) {
                    unreachable(e)
                    return changed
                } catch (e: Exception) {
                    // This one file: its manifest entry is unchanged, so the next run plans it again.
                    Log.w(TAG, "$action failed; the next run tries again.", e)
                }
            }
            mutableState.value = MirrorState.Idle
        } finally {
            save()
            if (mutableState.value is MirrorState.Syncing) mutableState.value = MirrorState.Idle
        }
        return changed
    }

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
