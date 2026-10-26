// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.NativeCallbacks
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import io.github.ilyalissoboi.goosestation.core.Resources
import io.github.ilyalissoboi.goosestation.input.ControllerInput
import io.github.ilyalissoboi.goosestation.mirror.Mirror
import io.github.ilyalissoboi.goosestation.mirror.MirrorState
import io.github.ilyalissoboi.goosestation.mirror.SafFolderStore
import io.github.ilyalissoboi.goosestation.storage.BiosImportResult
import io.github.ilyalissoboi.goosestation.storage.BiosImporter
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import io.github.ilyalissoboi.goosestation.storage.DataFolderContents
import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.storage.FolderPicker
import io.github.ilyalissoboi.goosestation.storage.FolderPrefs
import io.github.ilyalissoboi.goosestation.storage.GrantCheck
import io.github.ilyalissoboi.goosestation.storage.MissingGrant
import io.github.ilyalissoboi.goosestation.storage.SafDocumentLister
import io.github.ilyalissoboi.goosestation.storage.SavedFolders
import io.github.ilyalissoboi.goosestation.storage.SharedPreferencesStore
import io.github.ilyalissoboi.goosestation.storage.folderLabel
import io.github.ilyalissoboi.goosestation.storage.persistedGrants
import io.github.ilyalissoboi.goosestation.ui.components.BannerModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The app's screens. */
enum class Screen { Setup, Library, Folders, Game }

/**
 * The core, the folders, the mirror and what the app shows, for the life of the process, so a recreated activity
 * carries on. Main thread only, except the [NativeCallbacks], which the core calls on its threads: they post to the
 * main thread and never wait for it, since it may be waiting for the core in [NativeLibrary.surfaceChanged].
 *
 * The work behind it runs off the main thread: preferences, grants and SAF on [Dispatchers.IO], the mirror on its
 * own thread, and the core's library and settings calls on [coreDispatcher], one at a time.
 */
object AppController : NativeCallbacks {
    private const val TAG = "GooseStation"
    private const val PUSH_INTERVAL_MS = 2_000L
    private const val MEMCARDS = "memcards/"
    private const val UI = "UI"
    private const val GRID_VIEW = "GameListGridView"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    // refreshGameList and reloadSettings never overlap: reloadSettings rewrites the folder paths a scan reads.
    private val coreDispatcher = Dispatchers.IO.limitedParallelism(1)

    private lateinit var context: Context
    private lateinit var prefs: FolderPrefs
    private lateinit var biosImporter: BiosImporter
    private var started = false
    private var picker: FolderPicker? = null
    private var mirror: Mirror? = null
    private var pushTicker: Job? = null
    private var refreshJob: Job? = null

    /** The saved folders, as last loaded or saved. */
    private var folders = SavedFolders(bios = null, games = emptyList(), data = null, setupComplete = false)

    /** initialize() succeeded: from then on, starts are resumes. */
    private var coreStarted = false

    var screen by mutableStateOf(Screen.Library)
        private set

    /** Saved folders whose grant is gone, as the last check found. */
    var missingGrants by mutableStateOf(emptyList<MissingGrant>())
        private set

    private val mutableMirrorState = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val mirrorState: StateFlow<MirrorState> = mutableMirrorState.asStateFlow()

    /** The game list, as the last refresh found it. */
    var games by mutableStateOf(emptyList<GameEntry>())
        private set

    /** The library shows a cover grid, or else a list: `[UI] GameListGridView`, read once the core runs. */
    var gridView by mutableStateOf(true)
        private set

    /** The last game started, which the library focuses on coming back to it. */
    var lastGamePath by mutableStateOf<String?>(null)
        private set

    /** The core is initialized: games can start, and the game screen can show. */
    var ready by mutableStateOf(false)
        private set

    /** The latest error, or what the app is doing; one at a time. */
    var message by mutableStateOf<String?>("Starting…")
        private set
    var messageIsError by mutableStateOf(false)
        private set

    /**
     * A game from startGame() isn't over yet: until its onGameStopped(), which always comes. No other game starts
     * meanwhile, so every callback is for this one, even after the user has left it.
     */
    var gameActive by mutableStateOf(false)
        private set

    /**
     * [gameActive], for the mirror's thread: set before a game starts, cleared once it has stopped. While it's set,
     * the working copy's memory cards are never replaced.
     */
    @Volatile private var holdMemcards = false

    /** The core has a game running, from onGameStarted to onGameStopped. */
    var gameRunning = false
        private set

    var resumed = false
    var controllers: ControllerInput? = null
        private set

    private val workingCopy get() = File(context.filesDir, "data")
    private val dataGranted get() = missingGrants.none { it.kind == FolderKind.Data }

    /** Starts the cold-start sequence, once per process. */
    fun start(appContext: Context) {
        if (started) return
        started = true
        context = appContext
        prefs = FolderPrefs(SharedPreferencesStore(appContext))
        biosImporter = BiosImporter(appContext)
        // The core calls it for content:// paths from its first use on.
        ContentBridge.init(appContext)
        scope.launch { coldStart(withContext(Dispatchers.IO) { prefs.load() }) }
    }

    /** The activity's picker, registered in its onCreate; null when it's gone. */
    fun attach(folderPicker: FolderPicker) {
        picker = folderPicker
    }

    fun detach(folderPicker: FolderPicker) {
        if (picker === folderPicker) picker = null
    }

    /** The activity is visible: pushes every 2 s, and on a resume, the grants are checked and the folder pulled. */
    fun onStart() {
        pushTicker?.cancel()
        pushTicker =
            scope.launch {
                while (isActive) {
                    delay(PUSH_INTERVAL_MS)
                    push(force = false)
                }
            }
        if (coreStarted) scope.launch { resume() }
    }

    /** The activity is hidden: everything changed is pushed now, settled or not. */
    fun onStop() {
        pushTicker?.cancel()
        pushTicker = null
        scope.launch { push(force = true) }
    }

    // --- Setup ---

    suspend fun importBios(treeUri: String): BiosImportResult = biosImporter.import(treeUri)

    suspend fun inspectDataFolder(treeUri: String): DataFolderContents? =
        withContext(Dispatchers.IO) { DataFolderContents.read(SafDocumentLister(context.contentResolver), treeUri) }

    /** Lets go of a folder setup picked and then dropped, unless a saved folder still uses it. */
    fun releaseFolder(uri: String) {
        val saved = folders
        val picker = picker ?: return
        scope.launch(Dispatchers.IO) { picker.release(uri, saved) }
    }

    /** Saves the folders setup chose, then starts as a set-up app does. */
    fun finishSetup(chosen: SavedFolders) {
        // Once: a second Finish, pressed before the library shows, would start everything twice.
        if (screen != Screen.Setup) return
        folders = chosen
        message = "Starting…"
        messageIsError = false
        screen = Screen.Library
        scope.launch {
            save()
            coldStart(chosen)
        }
    }

    // --- Missing grants ---

    /** The banners for [missingGrants], and for a data folder that can't be reached in [state]. */
    fun banners(state: MirrorState): List<BannerModel> =
        LibraryBanners.build(missingGrants, state, folders.data != null, ::folderLabel, ::repick, ::removeGamesFolder)

    /**
     * Picks [grant]'s folder again. The same folder restores it. Another one replaces a games or BIOS folder (the BIOS
     * is imported again), but not the data folder, which only the folder settings change.
     */
    fun repick(grant: MissingGrant) {
        val picker = picker ?: return
        scope.launch {
            val picked = picker.pick(grant.kind) ?: return@launch
            if (picked != grant.uri) {
                when (grant.kind) {
                    FolderKind.Data -> {
                        release(picked)
                        showMessage("That isn't your data folder, so it wasn't changed. To use another, go to Folders.")
                        return@launch
                    }
                    FolderKind.Bios -> {
                        val result = biosImporter.import(picked)
                        if (result.images.isEmpty()) {
                            release(picked)
                            showError(result.error ?: "No PS1 BIOS found in this folder. Pick another.")
                            return@launch
                        }
                        folders = folders.copy(bios = picked)
                    }
                    FolderKind.Games -> {
                        val games = folders.games.map { if (it == grant.uri) picked else it }.distinct()
                        folders = folders.copy(games = games)
                    }
                }
                save()
                release(grant.uri)
            }
            checkGrants()
            when (grant.kind) {
                FolderKind.Data -> if (dataGranted) pull(onlyMemcards = false)
                FolderKind.Games -> updateLibrary()
                FolderKind.Bios -> {}
            }
        }
    }

    /** Stops listing a games folder whose grant is gone, and releases it. */
    fun removeGamesFolder(uri: String) {
        scope.launch {
            folders = folders.copy(games = folders.games - uri)
            save()
            release(uri)
            checkGrants()
            updateLibrary()
        }
    }

    // --- The library ---

    /** Switches between the grid and the list, and saves the choice in settings.ini. */
    fun toggleView() {
        val grid = !gridView
        gridView = grid
        if (coreStarted) scope.launch(coreDispatcher) { NativeLibrary.setBoolSetting(UI, GRID_VIEW, grid) }
    }

    /** Scans the games folders again, unless a scan from here is under way. */
    fun refresh() {
        if (!coreStarted || refreshJob?.isActive == true) return
        showMessage("Scanning your games…")
        refreshJob = scope.launch { refreshLibrary(showProgress = true) }
    }

    fun openFolders() {
        if (screen == Screen.Library) screen = Screen.Folders
    }

    fun closeFolders() {
        if (screen == Screen.Folders) screen = Screen.Library
    }

    // --- Games ---

    fun startGame(game: GameEntry) {
        if (!ready || gameActive) return
        lastGamePath = game.path
        message = null
        messageIsError = false
        gameActive = true
        holdMemcards = true
        screen = Screen.Game
        // It boots once the game screen's surface exists.
        NativeLibrary.startGame(game.path)
    }

    // Back to the library, which waits for the game's onGameStopped() before it starts another.
    fun leaveGame() {
        screen = Screen.Library
        if (!messageIsError) message = "Stopping the game…"
        NativeLibrary.shutdownGame()
    }

    // --- The sequences ---

    /** Grants, then the pull, the core's start, its game folders and the library, as [StartupPlan] says. */
    private suspend fun coldStart(saved: SavedFolders) {
        folders = saved
        if (saved.setupComplete) checkGrants()
        val steps = StartupPlan.steps(saved, missingGrants, coreStarted = false)
        if (StartupStep.ShowSetup in steps) {
            message = null
            screen = Screen.Setup
            return
        }
        screen = Screen.Library
        // A missing grant still has a mirror, for when the folder is picked again.
        saved.data?.let { data ->
            mirror =
                Mirror(
                    SafFolderStore(context.contentResolver, data),
                    workingCopy,
                    File(context.filesDir, "mirror-manifest.txt"),
                    System::currentTimeMillis,
                ).also { mirror -> scope.launch { mirror.state.collect { mutableMirrorState.value = it } } }
        }
        for (step in steps) {
            when (step) {
                StartupStep.Pull -> firstPull()
                StartupStep.InitializeCore -> if (!initializeCore()) return
                StartupStep.SetGameFolders -> applyGameFolders()
                StartupStep.RefreshLibrary -> refreshLibrary(showProgress = true)
                StartupStep.ShowSetup, StartupStep.ReloadSettings -> {}
            }
        }
    }

    /** Back in front: grants again, then a pull, settings.ini reloaded if it changed, and the library refreshed. */
    private suspend fun resume() {
        checkGrants()
        // A pull that reloaded the settings has rescanned the library already.
        var refreshed = false
        for (step in StartupPlan.steps(folders, missingGrants, coreStarted = true)) {
            when (step) {
                StartupStep.Pull -> refreshed = pull(onlyMemcards = false)
                StartupStep.RefreshLibrary -> if (screen == Screen.Library && !refreshed) refreshLibrary()
                else -> {}
            }
        }
    }

    // The first pull, before the core reads settings.ini, with its progress shown.
    private suspend fun firstPull() {
        val mirror = mirror ?: return
        val copying = "Copying your data from the data folder…"
        message = copying
        val progress =
            scope.launch {
                mirror.state.collect { state ->
                    // done counts the actions finished, so the one under way is done + 1.
                    if (state is MirrorState.Syncing) message = "$copying (${state.done + 1} of ${state.total})"
                }
            }
        mirror.pull { false }
        progress.cancel()
    }

    /**
     * A pull of everything but the running game's memory cards (only the memory cards when [onlyMemcards]), then what
     * [StartupPlan.afterPull] says. Only once the core runs, so never before the first pull, and never without the
     * data grant. Returns whether that refreshed the library.
     */
    private suspend fun pull(onlyMemcards: Boolean): Boolean {
        val mirror = mirror ?: return false
        if (!coreStarted || !dataGranted) return false
        // Asked for each file as the mirror acts, so a game started meanwhile keeps its cards.
        val result =
            mirror.pull { path ->
                val memcard = path.startsWith(MEMCARDS)
                if (onlyMemcards) !memcard else holdMemcards && memcard
            }
        return applyChanged(result.changed)
    }

    // As pull(): a push can copy files in as well, where the folder's side changed too.
    private suspend fun push(force: Boolean) {
        val mirror = mirror ?: return
        if (!coreStarted || !dataGranted) return
        applyChanged(mirror.push(force) { holdMemcards && it.startsWith(MEMCARDS) }.changed)
    }

    /**
     * A settings.ini the mirror replaced is read again, before the core can save its old settings over it, then the
     * game folders are set again and the library rescanned, as [StartupPlan.afterPull] says: in one go on
     * [coreDispatcher], so no scan runs between the reload and the folders. Returns whether it refreshed the library.
     */
    private suspend fun applyChanged(changed: Set<String>): Boolean {
        val steps = StartupPlan.afterPull(changed)
        if (steps.isEmpty()) return false
        val readable = readableGameFolders()
        val refreshed =
            withContext(coreDispatcher) {
                for (step in steps) {
                    when (step) {
                        StartupStep.ReloadSettings -> {
                            NativeLibrary.reloadSettings()
                            readGridView()
                        }
                        StartupStep.SetGameFolders -> NativeLibrary.setGameFolders(readable)
                        StartupStep.RefreshLibrary -> NativeLibrary.refreshGameList(false)
                        else -> {}
                    }
                }
                if (StartupStep.RefreshLibrary in steps) NativeLibrary.getGameList().toList() else null
            }
        refreshed?.let { games = it }
        return refreshed != null
    }

    // initialize() waits for the core's thread to start, and the resources are copied out first.
    private suspend fun initializeCore(): Boolean {
        message = "Starting the core…"
        val result =
            withContext(coreDispatcher) {
                runCatching {
                    val biosDir = File(context.filesDir, "bios").apply { mkdirs() }
                    workingCopy.mkdirs()
                    val resources = Resources.extract(context)
                    NativeLibrary.setCallbacks(this@AppController)
                    NativeLibrary.initialize(
                        workingCopy.absolutePath,
                        resources.absolutePath,
                        biosDir.absolutePath,
                        prefs.installId(),
                    ).also { if (it) readGridView() }
                }
            }
        // onError(), posted before initialize() returned, has already run.
        val exception = result.exceptionOrNull()
        if (!result.getOrDefault(false)) {
            Log.e(TAG, "The core failed to start.", exception)
            if (!messageIsError || exception != null) {
                showError("The core failed to start: ${exception?.message ?: "unknown error"}")
            }
            return false
        }
        Log.i(TAG, "The core is ready.")
        coreStarted = true
        ready = true
        if (!messageIsError) message = null
        // Reported once the core takes input; it keeps connections made before then anyway.
        controllers = ControllerInput(context).also { it.start() }
        return true
    }

    // On coreDispatcher, once the core has read settings.ini.
    private suspend fun readGridView() {
        val grid = NativeLibrary.getBoolSetting(UI, GRID_VIEW, true)
        withContext(Dispatchers.Main) { gridView = grid }
    }

    // The games folders the app can read: one without its grant is left out until it's picked again.
    private fun readableGameFolders(): Array<String> {
        val unreadable = missingGrants.filter { it.kind == FolderKind.Games }.map { it.uri }.toSet()
        return folders.games.filterNot { it in unreadable }.toTypedArray()
    }

    private suspend fun applyGameFolders() {
        val readable = readableGameFolders()
        withContext(coreDispatcher) { NativeLibrary.setGameFolders(readable) }
    }

    // After a games folder change. Before the core runs, its start passes the folders itself.
    private suspend fun updateLibrary() {
        if (!coreStarted) return
        applyGameFolders()
        refreshLibrary()
    }

    private suspend fun refreshLibrary(showProgress: Boolean = false) {
        if (!coreStarted) return
        if (showProgress && !messageIsError) message = "Scanning your games…"
        games =
            withContext(coreDispatcher) {
                NativeLibrary.refreshGameList(false)
                NativeLibrary.getGameList().toList()
            }
        if (showProgress && !messageIsError) message = null
    }

    private suspend fun checkGrants() {
        val saved = folders
        missingGrants =
            withContext(Dispatchers.IO) { GrantCheck.missing(saved, context.contentResolver.persistedGrants()) }
    }

    private suspend fun save() {
        val saved = folders
        if (!withContext(Dispatchers.IO) { prefs.save(saved) }) showError("Can't save your folders.")
    }

    private suspend fun release(uri: String) {
        val picker = picker ?: return
        val saved = folders
        withContext(Dispatchers.IO) { picker.release(uri, saved) }
    }

    private fun showMessage(text: String) {
        message = text
        messageIsError = false
    }

    private fun showError(text: String) {
        message = text
        messageIsError = true
    }

    // --- NativeCallbacks ---

    override fun onGameStarted(title: String) {
        mainHandler.post {
            Log.i(TAG, "Game started: $title")
            gameRunning = true
            // Release on this thread, after any input it already sent during the boot, so nothing pressed
            // while booting can stay held.
            controllers?.releaseAll()
            // Booted in the background. One the user left while it booted is shut down next anyway.
            if (screen == Screen.Game && !resumed) NativeLibrary.setPaused(true)
        }
    }

    override fun onGameStopped() {
        mainHandler.post {
            Log.i(TAG, "Game stopped.")
            gameRunning = false
            // Same as on start: release in order with this thread's input.
            controllers?.releaseAll()
            gameActive = false
            holdMemcards = false
            if (screen == Screen.Game) screen = Screen.Library
            if (!messageIsError) message = null
            // The memory cards held back while it ran come in now, then the game's own writes go out.
            scope.launch {
                pull(onlyMemcards = true)
                push(force = true)
            }
        }
    }

    override fun onError(message: String) {
        mainHandler.post {
            Log.w(TAG, "Error: $message")
            // A failed boot can report twice; the latest one shows. onGameStopped() follows a failed boot, and takes
            // the app back to the library, where it shows.
            showError(message)
        }
    }
}
