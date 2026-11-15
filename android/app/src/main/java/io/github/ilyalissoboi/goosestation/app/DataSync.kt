// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.util.Log
import io.github.ilyalissoboi.goosestation.mirror.CopyGuard
import io.github.ilyalissoboi.goosestation.mirror.DataLayout
import io.github.ilyalissoboi.goosestation.mirror.MirrorRuns
import io.github.ilyalissoboi.goosestation.mirror.MirrorState
import io.github.ilyalissoboi.goosestation.mirror.PackMarkers
import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The data folder mirror's runs, and the state they decide by: which files a pull or push leaves (a running game's
 * memory cards), what follows one that brought in settings.ini or a game's settings, and the catch-up pulls that
 * follow a growth of the data layout, during which no game starts.
 *
 * It reaches the core through [core] and the app through [hooks], and makes each mirror with [makeMirror]: a mirror
 * of the data folder `data`, holding the working copy's files with `guard`, leaving out the shader packs
 * `packMarkers` names, removing strays when `removeStrays` says so, and copying `layout`. [log] takes a priority
 * ([Log.INFO], say) and a line.
 *
 * Main thread, except where a member says otherwise: the core's work runs on [coreDispatcher], and the mirror asks
 * for each file's decision on its own thread.
 */
class DataSync<M : MirrorRuns>(
    private val scope: CoroutineScope,
    private val coreDispatcher: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val core: SyncCore,
    private val hooks: SyncHooks,
    private val workingCopy: File,
    private val manifestFile: File,
    private val carryOverStore: KeyValueStore,
    private val makeMirror: (
        data: String,
        guard: CopyGuard,
        packMarkers: () -> PackMarkers?,
        removeStrays: () -> Boolean,
        layout: () -> DataLayout,
    ) -> M,
    private val log: (priority: Int, message: String) -> Unit,
) {
    /** The mirror of the data folder, once [useMirror] has made one. */
    var mirror: M? = null
        private set
    private var mirrorStateJob: Job? = null

    private val mutableMirrorState = MutableStateFlow<MirrorState>(MirrorState.Idle)

    /** What the [mirror] is doing: the last one [useMirror] made. */
    val mirrorState: StateFlow<MirrorState> = mutableMirrorState.asStateFlow()

    /**
     * Whether a game is active, for the mirror's thread: set before a game starts, cleared once it has stopped. While
     * it's set, the working copy's memory cards are never replaced.
     */
    @Volatile var holdMemcards = false

    /**
     * Where the memory cards are in the working copy ([MemoryCardPaths]), as the core last said, with the card files
     * it said earlier in this run ([MemoryCardPaths.merge]). Read again as a game starts and stops: a running game's
     * own settings can name other cards.
     */
    @Volatile private var memcardPaths = MemoryCardPaths.DEFAULT

    /**
     * What the mirror copies ([DataLayout]): the defaults until the core, once it has started, says otherwise. Written
     * on [coreDispatcher], read on the mirror's thread.
     */
    @Volatile var dataLayout = DataLayout.DEFAULT
        private set

    /**
     * Whether a complete pull has found nothing that grows the [dataLayout] since it last grew: until then, the mirror
     * leaves the working copy's strays alone, as the layout may still lack the memory cards of game settings not yet
     * pulled. Asked on the mirror's thread.
     */
    private val layoutSettling = LayoutSettling()

    /** The pulls that follow a growth of the layout ([catchUp]), one at a time, and whether a game may start. */
    private val catchUps = CatchUpRunner { log(Log.WARN, it) }

    /** No game starts while this is false: a catch-up is due or under way ([CatchUpRunner.startsAllowed]). */
    val startsAllowed get() = catchUps.startsAllowed

    /** [CatchUpRunner.keepsLine]: whether the line stays as a game stops, as a start refused meanwhile said why. */
    fun keepsLine(refusalShown: Boolean) = catchUps.keepsLine(refusalShown)

    // Memory card sync never writes a card while the mirror replaces or deletes it: sync holds the card meanwhile.
    // Before the core starts, nothing syncs.
    private val cardGuard =
        CopyGuard { path, action ->
            if (hooks.coreStarted() && MemoryCardPaths.matches(path, memcardPaths)) {
                core.holdCard(path) { action() }
            } else {
                action()
                true
            }
        }

    // A desktop's shader packs in the data folder: the mirror leaves them, and their markers, out. The core names its
    // shaders folder once it's started; "shaders", its default, before.
    private val shaderPackIds by lazy { core.shaderPackIds() }

    fun packMarkers(): PackMarkers? {
        val shaders = if (hooks.coreStarted()) core.getShadersFolder() ?: return null else "shaders"
        return PackMarkers(shaders, shaderPackIds) { core.shaderPackMarkerFiles(it) }
    }

    /** A mirror of [data], whose state the app shows from now on. */
    fun useMirror(data: String) {
        mirrorStateJob?.cancel()
        val created =
            makeMirror(
                data,
                cardGuard,
                ::packMarkers,
                { layoutSettling.settled },
            ) { dataLayout }
        mirror = created
        mirrorStateJob = scope.launch { created.state.collect { mutableMirrorState.value = it } }
    }

    // The first pull, before the core reads settings.ini, with its progress shown.
    suspend fun firstPull() {
        val mirror = mirror ?: return
        prepareWorkingCopy()
        hooks.showCopying(null)
        val progress =
            scope.launch {
                mirror.state.collect { state ->
                    // done counts the actions finished, so the one under way is done + 1.
                    if (state is MirrorState.Syncing) hooks.showCopying(state.done + 1 to state.total)
                }
            }
        mirror.pull { false }
        progress.cancel()
    }

    /**
     * Creates the working copy if it's missing (a fresh install), which the mirror needs to run. A manifest without
     * one describes files that are gone: it's deleted, so the folder's files all come in rather than read as deleted
     * here.
     */
    private suspend fun prepareWorkingCopy() =
        withContext(io) {
            EmusyncCarryOver.drop(workingCopy, carryOverStore)
            if (workingCopy.isDirectory) return@withContext
            if (manifestFile.delete()) log(Log.WARN, "The working copy is gone: pulling the data folder in full.")
            if (!workingCopy.mkdirs()) log(Log.ERROR, "Can't create $workingCopy")
        }

    /**
     * A pull of everything but the running game's memory cards (only the memory cards when [onlyMemcards]), then what
     * [StartupPlan.afterPull] says, and a [catchUp] if that grew the layout, however it ends
     * ([CatchUpRunner.guardGrowth]). Only once the core runs, so never before the first pull, and never without the
     * data grant. Returns whether that refreshed the library.
     */
    suspend fun pull(onlyMemcards: Boolean): Boolean =
        catchUps.guardGrowth(::catchUp) { onGrowth -> pullOnce(onlyMemcards, onGrowth).refreshed }

    /**
     * A catch-up's pull ([CatchUp.run]): as [pull], but returns whether it grew the layout, for the next one. Its
     * growth isn't flagged: the run refuses starts while it lasts.
     */
    private suspend fun pullGrew(): Boolean {
        val grew = pullOnce(onlyMemcards = false, onGrowth = {}).layoutGrew
        log(Log.INFO, "Catch-up pull: the data layout ${if (grew) "grew" else "stopped growing"}.")
        return grew
    }

    /**
     * A pull, then [applyChanged], with [onGrowth]. A pull of everything that did its whole plan settles the layout,
     * unless it grew meanwhile ([LayoutSettling.pulled]).
     */
    private suspend fun pullOnce(
        onlyMemcards: Boolean,
        onGrowth: () -> Unit,
    ): Applied {
        val mirror = mirror ?: return Applied.NOTHING
        if (!hooks.coreStarted() || !hooks.dataGranted()) return Applied.NOTHING
        val pull = layoutSettling.pullStarting()
        // Asked for each file as the mirror acts, so a game started meanwhile keeps its cards.
        val result = mirror.pull { path -> MemoryCardPaths.leftByPull(path, memcardPaths, onlyMemcards, holdMemcards) }
        val applied = applyChanged(result.changed, onGrowth)
        if (result.changed.isNotEmpty()) hooks.refreshEditor()
        // Only the memory cards: game settings that name more files may be among those it left.
        if (!onlyMemcards) layoutSettling.pulled(pull, result.complete)
        return applied
    }

    // As pull(): a push can copy files in as well, where the folder's side changed too. The ticker's is cancelled as
    // the app goes to the background, maybe just after it grew the layout.
    suspend fun push(force: Boolean) {
        val mirror = mirror ?: return
        if (!hooks.coreStarted() || !hooks.dataGranted()) return
        catchUps.guardGrowth(::catchUp) { onGrowth ->
            val result = mirror.push(force) { MemoryCardPaths.deferred(it, memcardPaths, holdMemcards) }
            applyChanged(result.changed, onGrowth)
            if (result.changed.isNotEmpty()) hooks.refreshEditor()
        }
    }

    /**
     * Pulls until the layout stops growing ([CatchUp]): the folders and cards that new settings.ini or game settings
     * name, which the run that brought them left out. Until it's done no game starts ([CatchUpRunner]), and strays
     * stay until a complete pull settles the layout ([layoutSettling]), maybe after it's done. One under way pulls
     * again once it's done instead, from the layout as it is then: its job is returned. Main thread only.
     */
    fun catchUp(): Job? {
        layoutSettling.unsettle()
        return catchUps.launch(
            scope,
            // Not while starts are still refused, as by a catch-up run again: a start refused meanwhile says why. The
            // line it'd keep is the plain "Copying…" a refused start shows (CatchUpRunner.keepsLine).
            onEnd = { if (hooks.copyingShown() && catchUps.startsAllowed) hooks.clearCopying() },
        ) { pullGrew() }
    }

    /** What [applyChanged] did: whether it [refreshed] the library, and whether the core's paths [layoutGrew]. */
    private class Applied(
        val refreshed: Boolean,
        val layoutGrew: Boolean,
    ) {
        companion object {
            val NOTHING = Applied(refreshed = false, layoutGrew = false)
        }
    }

    /**
     * A settings.ini the mirror replaced is read again, before the core can save its old settings over it, then the
     * game folders are set again and the library rescanned, as [StartupPlan.afterPull] says: in one go on
     * [coreDispatcher], so no scan runs between the reload and the folders. A game's settings it replaced have the
     * core's paths read again, as they can name a memory card. If that grows the layout, [onGrowth] runs at once, on
     * [coreDispatcher], before the steps that follow: from outside a catch-up, it refuses starts until the catch-up
     * the caller then asks for is done.
     */
    private suspend fun applyChanged(
        changed: Set<String>,
        onGrowth: () -> Unit,
    ): Applied {
        val steps = StartupPlan.afterPull(changed)
        if (steps.isEmpty()) return Applied.NOTHING
        val readable = hooks.readableGameFolders()
        var layoutGrew = false
        val refreshed =
            withContext(coreDispatcher) {
                layoutGrew =
                    StartupPlan.carryOut(steps, onGrowth) { step ->
                        when (step) {
                            StartupStep.ReloadSettings -> {
                                core.reloadSettings()
                                hooks.onSettingsReloadedOnCore()
                                readCorePaths()
                            }
                            StartupStep.ReadCorePaths -> readCorePaths()
                            StartupStep.SetGameFolders -> false.also { core.setGameFolders(readable) }
                            StartupStep.RefreshLibrary -> false.also { core.refreshGameList(false) }
                            else -> false
                        }
                    }
                if (StartupStep.RefreshLibrary in steps) core.getGameList() else null
            }
        refreshed?.let { hooks.onLibraryRead(it) }
        hooks.onSettingsReloaded(steps)
        return Applied(refreshed != null, layoutGrew)
    }

    /**
     * The memory card paths and the [dataLayout], as the core has them; returns whether the layout gained a folder or
     * file, which unsettles it ([layoutSettling]). On coreDispatcher, once the core has read settings.ini.
     */
    fun readCorePaths(): Boolean {
        val reported = core.getMemoryCardPaths() ?: MemoryCardPaths.DEFAULT
        memcardPaths = MemoryCardPaths.merge(reported, memcardPaths)
        val folders = core.getDataFolders() ?: return false
        val files = core.getDataFiles().orEmpty()
        val layout = DataLayout.withCorePaths(folders + files + memcardPaths, core.getCacheFolder())
        val grew = layout.addsTo(dataLayout)
        // Before a mirror run can start with it.
        if (grew) layoutSettling.unsettle()
        dataLayout = layout
        return grew
    }
}
