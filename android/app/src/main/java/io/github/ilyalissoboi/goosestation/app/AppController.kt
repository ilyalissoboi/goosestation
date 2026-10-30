// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.NativeCallbacks
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.Resources
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.input.ControllerInput
import io.github.ilyalissoboi.goosestation.mirror.DataFolderChange
import io.github.ilyalissoboi.goosestation.mirror.DataFolderChoice
import io.github.ilyalissoboi.goosestation.mirror.DataLayout
import io.github.ilyalissoboi.goosestation.mirror.Mirror
import io.github.ilyalissoboi.goosestation.mirror.MirrorState
import io.github.ilyalissoboi.goosestation.mirror.SafFolderStore
import io.github.ilyalissoboi.goosestation.settings.BiosFastBoot
import io.github.ilyalissoboi.goosestation.settings.BiosOverrides
import io.github.ilyalissoboi.goosestation.settings.BiosPage
import io.github.ilyalissoboi.goosestation.settings.BiosRegion
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.NativeCoreSettings
import io.github.ilyalissoboi.goosestation.storage.BiosImportResult
import io.github.ilyalissoboi.goosestation.storage.BiosImporter
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import io.github.ilyalissoboi.goosestation.storage.DataFolderCheck
import io.github.ilyalissoboi.goosestation.storage.DataFolderInspection
import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.storage.FolderPicker
import io.github.ilyalissoboi.goosestation.storage.FolderPrefs
import io.github.ilyalissoboi.goosestation.storage.GrantCheck
import io.github.ilyalissoboi.goosestation.storage.MissingGrant
import io.github.ilyalissoboi.goosestation.storage.SavedFolders
import io.github.ilyalissoboi.goosestation.storage.SharedPreferencesStore
import io.github.ilyalissoboi.goosestation.storage.VolumeMatch
import io.github.ilyalissoboi.goosestation.storage.folderLabel
import io.github.ilyalissoboi.goosestation.storage.persistedGrants
import io.github.ilyalissoboi.goosestation.ui.DataFolderCandidate
import io.github.ilyalissoboi.goosestation.ui.SettingsEntry
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
import kotlin.system.exitProcess

/** The app's screens: the settings hub, and its pages ([Screen.page]), open from the library. */
enum class Screen(
    /** The hub's entry for this page of the settings; null for any other screen. */
    val page: SettingsEntry? = null,
) {
    Setup,
    Library,
    Settings,
    Controllers(SettingsEntry.Controllers),
    Graphics(SettingsEntry.Graphics),
    Bios(SettingsEntry.Bios),
    Folders(SettingsEntry.Folders),
    Game,
}

/**
 * The Controllers page: the [controllers] connected, the [selected] one's descriptor, and its [bindings], null until
 * they're first read. While [capturing] is a binding's name, the next input on that controller becomes its binding,
 * unless [secondsLeft] runs out first.
 */
data class ControllersState(
    val controllers: List<ConnectedController> = emptyList(),
    val selected: String? = null,
    val bindings: List<PadBinding>? = null,
    val capturing: String? = null,
    val secondsLeft: Int = 0,
)

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
    private const val UI = "UI"
    private const val GRID_VIEW = "GameListGridView"
    private const val CARD_REMOVED = "the SD card was removed"
    private const val GAME_STOPPED_FOR_EJECT =
        "The SD card was removed, so the game was stopped. Its memory cards are saved on this device."
    private const val CAPTURE_SECONDS = 5
    private const val COPYING = "Copying your data from the data folder…"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    // refreshGameList and reloadSettings never overlap: reloadSettings rewrites the folder paths a scan reads.
    private val coreDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Saves in the order they're made, so an older one never lands last.
    private val prefsDispatcher = Dispatchers.IO.limitedParallelism(1)

    private lateinit var context: Context
    private lateinit var prefs: FolderPrefs
    private lateinit var biosImporter: BiosImporter
    private var started = false
    private val currentActivity = CurrentActivity<Activity>()
    private val activity get() = currentActivity.current
    private var picker: FolderPicker? = null
    private var mirror: Mirror? = null
    private var mirrorStateJob: Job? = null
    private var pushTicker: Job? = null
    private var refreshJob: Job? = null
    private var biosJob: Job? = null

    /** The volumes being ejected, or gone, since their eject: each is handled once, until it's mounted again. */
    private val ejectedVolumes = HashSet<String>()

    /** The saved folders, as last loaded or saved. */
    var folders by mutableStateOf(SavedFolders(bios = null, games = emptyList(), data = null, setupComplete = false))
        private set

    /** The BIOS images the last import copied, as it described them. */
    var biosImages by mutableStateOf(emptyList<String>())
        private set

    /** A folder picked to become the data folder, waiting for the user's choice in the folder settings. */
    var dataCandidate by mutableStateOf<DataFolderCandidate?>(null)
        private set

    /**
     * The last try at changing to [dataCandidate] found changes that didn't reach the old data folder: the choice is
     * shown again, warning that using the new folder's data loses them.
     */
    private var oldFolderMissedChanges by mutableStateOf(false)

    /** What a data folder change under way is doing; null when none is. It ends in a restart. */
    var dataChange by mutableStateOf<String?>(null)
        private set

    /** The library focuses its Settings button when it opens, as it does coming back from the settings. */
    var focusSettings by mutableStateOf(false)
        private set

    /** The hub's entry for the page last left, which the hub focuses; null on opening it from the library. */
    var settingsEntry by mutableStateOf<SettingsEntry?>(null)
        private set

    /** The Graphics page's settings, as loaded on opening it; null until they are. */
    var graphics by mutableStateOf<GraphicsSettings?>(null)
        private set

    /** The core's `[Display] AspectRatio` names, read with [graphics]. */
    var aspectRatioNames by mutableStateOf(emptyList<String>())
        private set

    /** Vulkan didn't start in this run, as the core said when [graphics] were read. */
    var vulkanFailed by mutableStateOf(false)
        private set

    /** The BIOS page's images and settings, as read on opening it; null until they are. */
    var bios by mutableStateOf<BiosPage?>(null)
        private set

    /** The Controllers page's controllers, bindings and capture. */
    var controllersState by mutableStateOf(ControllersState())
        private set

    /** A capture runs: the controllers' input goes to the core, which takes the next one as a binding. */
    val capturingInput: Boolean get() = controllersState.capturing != null

    /** The controller last picked on the Controllers page, which it selects whenever it's connected. */
    private var pickedController: String? = null
    private var controllersJob: Job? = null
    private var captureTimer: Job? = null

    /** The latest bindings read, so an older read that comes back later is dropped. */
    private var bindingsRead = 0

    /** Changes as each capture starts or ends, so a report the main thread gets late is dropped. */
    private var captureId = 0

    /** [captureId] of the capture the core runs: set on [coreDispatcher], read where the core reports. */
    @Volatile private var nativeCaptureId = 0

    /** An import copied images before the core started: the overrides they leave stale are dropped once it has. */
    private var biosOverridesToCheck = false

    /** initialize() succeeded: from then on, starts are resumes. */
    private var coreStarted = false

    /** The cold-start sequence is under way: the data folder can't change until it's done. */
    private var coldStarting = false

    var screen by mutableStateOf(Screen.Library)
        private set

    /**
     * The game's prompt (Resume or Quit to library) shows over it: the game is paused, and the controllers move the
     * prompt's focus rather than play.
     */
    var promptOpen by mutableStateOf(false)
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

    /** The core is initialized, and the data folder pulled in full: games can start, and the game screen can show. */
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

    /**
     * Where the memory cards are in the working copy ([MemoryCardPaths]), as the core last said, with the card files
     * it said earlier in this run ([MemoryCardPaths.merge]). Read again as a game starts and stops: a running game's
     * own settings can name other cards.
     */
    @Volatile private var memcardPaths = MemoryCardPaths.DEFAULT

    /** What the mirror copies ([DataLayout]): the defaults until the core, once it has started, says otherwise. */
    @Volatile private var dataLayout = DataLayout.DEFAULT

    /**
     * Whether a complete pull has found nothing that grows the [dataLayout] since it last grew: until then, the mirror
     * leaves the working copy's strays alone, as the layout may still lack the memory cards of game settings not yet
     * pulled. Asked on the mirror's thread.
     */
    private val layoutSettling = LayoutSettling()

    /** The pulls that follow a growth of the layout ([catchUp]), one at a time, and whether a game may start. */
    private val catchUps = CatchUpRunner { Log.w(TAG, it) }

    /** The catch-up running, or the last one; main thread only. */
    private var catchUpJob: Job? = null

    /** The core has a game running, from onGameStarted to onGameStopped. */
    var gameRunning = false
        private set

    var resumed = false

    /**
     * The app paused the game while it booted ([pauseGame]). That pause can reach the core just after the boot, so
     * [onGameStarted] unpauses the game if it may run by then. Reset as the game starts or stops.
     */
    private var pausedDuringBoot = false
    var controllers: ControllerInput? = null
        private set

    private val workingCopy get() = File(context.filesDir, "data")
    private val manifestFile get() = File(context.filesDir, "mirror-manifest.txt")
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
        watchVolumes(appContext)
        scope.launch {
            val (saved, images) = withContext(Dispatchers.IO) { prefs.load() to prefs.biosImages() }
            biosImages = images
            coldStart(saved)
        }
    }

    /**
     * The activity, and its picker, registered in its onCreate; null when they're gone. It replaces any other: the
     * lifecycle calls below, from an activity that isn't this one, are ignored ([CurrentActivity]).
     */
    fun attach(
        activity: Activity,
        folderPicker: FolderPicker,
    ) {
        currentActivity.attach(activity)
        picker = folderPicker
    }

    fun detach(activity: Activity) {
        if (!currentActivity.isCurrent(activity)) return
        currentActivity.detach(activity)
        picker = null
    }

    /** Whether [activity] is the one the app is shown in, the last attached. */
    fun isCurrent(activity: Activity): Boolean = currentActivity.isCurrent(activity)

    /**
     * [activity] is visible: pushes every 2 s, and on a resume, the grants are checked and the folder pulled. Ignored
     * unless it's the current activity.
     */
    fun onStart(activity: Activity) {
        if (!isCurrent(activity)) return
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

    /**
     * [activity] is hidden: everything changed is pushed now, settled or not. Ignored unless it's the current
     * activity: an old one stopping after its replacement started would end the new one's pushes.
     */
    fun onStop(activity: Activity) {
        if (!isCurrent(activity)) return
        pushTicker?.cancel()
        pushTicker = null
        scope.launch { push(force = true) }
    }

    // --- Setup ---

    /**
     * Copies the BIOS images in [treeUri], and remembers what it found, if anything. A region whose image isn't among
     * the new copies goes back to Automatic, once the core has started ([dropStaleBiosOverrides]).
     */
    suspend fun importBios(treeUri: String): BiosImportResult {
        val result = biosImporter.import(treeUri)
        if (result.images.isNotEmpty()) {
            biosImages = result.images
            withContext(prefsDispatcher) { prefs.saveBiosImages(result.images) }
            if (coreStarted) dropStaleBiosOverrides() else biosOverridesToCheck = true
        }
        return result
    }

    /** Whether [treeUri] can be the data folder alongside the [games] folders, and what it holds if it can. */
    suspend fun inspectDataFolder(
        treeUri: String,
        games: List<String>,
    ): DataFolderInspection =
        withContext(Dispatchers.IO) { DataFolderCheck.inspect(context.contentResolver, treeUri, games) }

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
     * is imported again); for the data folder, the folder settings open on the choice of what to do with it.
     */
    fun repick(grant: MissingGrant) {
        val picker = picker ?: return
        scope.launch {
            val picked = picker.pick(grant.kind) ?: return@launch
            if (picked != grant.uri) {
                when (grant.kind) {
                    FolderKind.Data -> {
                        offerDataFolder(picked)
                        if (dataCandidate?.uri == picked && screen == Screen.Library) screen = Screen.Folders
                        return@launch
                    }
                    FolderKind.Bios -> {
                        val result = importBios(picked)
                        if (result.images.isEmpty()) {
                            release(picked)
                            showError(result.error ?: "No PS1 BIOS found in this folder. Pick another.")
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
                FolderKind.Data -> if (dataGranted) pull(onlyMemcards = false)
                FolderKind.Games -> updateLibrary()
                FolderKind.Bios -> {}
            }
        }
    }

    /** Stops listing a games folder whose grant is gone, and releases it. */
    fun removeGamesFolder(uri: String) = setGamesFolders(folders.games - uri)

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

    // --- The settings ---

    /** Opens the settings hub, from the library. */
    fun openSettings() {
        if (screen != Screen.Library) return
        settingsEntry = null
        screen = Screen.Settings
    }

    /** Back to the library, which focuses its Settings button. */
    fun closeSettings() {
        if (screen != Screen.Settings) return
        focusSettings = true
        screen = Screen.Library
    }

    /** The settings pages that need the core can open: it has started, and no game is under way. */
    val settingsReady get() = ready && !gameActive

    /**
     * Opens the hub's [entry], if it can: all but Folders need [settingsReady]. The Graphics page shows once its
     * settings are read, on [coreDispatcher].
     */
    fun openSettingsPage(entry: SettingsEntry) {
        if (screen != Screen.Settings || (entry.needsCore && !settingsReady)) return
        when (entry) {
            SettingsEntry.Graphics -> {
                graphics = null
                scope.launch {
                    val loaded =
                        withContext(coreDispatcher) {
                            Triple(
                                GraphicsSettings.load(NativeCoreSettings),
                                NativeLibrary.getAspectRatioNames().toList(),
                                NativeLibrary.isVulkanFailed(),
                            )
                        }
                    aspectRatioNames = loaded.second
                    vulkanFailed = loaded.third
                    graphics = loaded.first
                }
            }
            SettingsEntry.Bios -> {
                bios = null
                scope.launch {
                    bios =
                        withContext(coreDispatcher) {
                            BiosPage(
                                images = NativeLibrary.getBiosImages().toList(),
                                auto = BiosRegion.entries.associateWith { NativeLibrary.getAutoBios(it.coreName) },
                                overrides = BiosOverrides.load(NativeCoreSettings),
                                fastBoot = BiosFastBoot.load(NativeCoreSettings),
                            )
                        }
                }
            }
            SettingsEntry.Controllers -> watchControllers()
            SettingsEntry.Folders -> {}
        }
        screen = Screen.entries.first { it.page == entry }
    }

    /** Back from a settings page to the hub, which focuses the page's entry. */
    fun closeSettingsPage() {
        val page = screen.page ?: return
        if (page == SettingsEntry.Folders) return closeFolders()
        if (page == SettingsEntry.Controllers) {
            cancelCapture()
            controllersJob?.cancel()
            controllersJob = null
        }
        settingsEntry = page
        screen = Screen.Settings
    }

    /**
     * Saves the Graphics page's change to [new], only the fields that changed, then has the core apply it, on
     * [coreDispatcher]. The mirror's next push takes settings.ini to the data folder.
     */
    fun changeGraphics(new: GraphicsSettings) {
        val old = graphics ?: return
        if (new == old) return
        // Now, so a second change made before this one is saved starts from it.
        graphics = new
        scope.launch(coreDispatcher) {
            new.save(NativeCoreSettings, old)
            NativeLibrary.applySettings()
        }
    }

    /** Sets [region]'s BIOS image to [fileName], or back to Automatic with null, then has the core apply it. */
    fun setBiosOverride(
        region: BiosRegion,
        fileName: String?,
    ) {
        val page = bios ?: return
        val overrides = if (fileName == null) page.overrides - region else page.overrides + (region to fileName)
        // Now, so a second change made before this one is saved starts from it.
        bios = page.copy(overrides = overrides)
        scope.launch(coreDispatcher) {
            BiosOverrides.set(NativeCoreSettings, region, fileName)
            NativeLibrary.applySettings()
        }
    }

    /** Turns fast boot on or off, then has the core apply it. */
    fun setFastBoot(on: Boolean) {
        val page = bios ?: return
        bios = page.copy(fastBoot = on)
        scope.launch(coreDispatcher) {
            BiosFastBoot.save(NativeCoreSettings, on)
            NativeLibrary.applySettings()
        }
    }

    // --- The Controllers page ---

    /** Shows the controller with [descriptor]'s bindings, and selects it whenever it's connected from now on. */
    fun selectController(descriptor: String) {
        pickedController = descriptor
        val state = controllersState
        if (descriptor == state.selected || state.controllers.none { it.descriptor == descriptor }) return
        cancelCapture()
        controllersState = controllersState.copy(selected = descriptor)
        readBindings(descriptor)
    }

    /**
     * Captures the selected controller's next input as [name]'s binding ([onBindingCaptured]): for 5 s, counting down
     * once a second, and then it's cancelled. Its controllers' input goes to the core meanwhile ([capturingInput]).
     */
    fun captureBinding(name: String) {
        val state = controllersState
        val descriptor = state.selected ?: return
        if (screen != Screen.Controllers || state.bindings?.any { it.name == name } != true) return
        val id = ++captureId
        captureTimer?.cancel()
        controllersState = state.copy(capturing = name, secondsLeft = CAPTURE_SECONDS)
        scope.launch {
            // Nothing from an earlier capture comes once the cancel returns: what's reported next is this one's.
            withContext(coreDispatcher) { NativeLibrary.cancelBindingCapture() }
            if (id != captureId) return@launch
            // Here, on the main thread, after the releaseAll() an earlier capture's end runs once its cancel returns,
            // which was before this one's: the pad's current axes, so the capture knows which are at rest. The core
            // takes in the input sent before the start.
            controllers?.sendAxes(descriptor)
            withContext(coreDispatcher) {
                nativeCaptureId = id
                NativeLibrary.startBindingCapture(descriptor)
            }
        }
        captureTimer =
            scope.launch {
                for (left in CAPTURE_SECONDS downTo 1) {
                    controllersState = controllersState.copy(secondsLeft = left)
                    delay(1_000)
                }
                cancelCapture()
            }
    }

    /** Ends the capture running, if any, leaving the binding as it was. */
    fun cancelCapture() {
        if (controllersState.capturing == null) return
        endCapture()
        scope.launch {
            withContext(coreDispatcher) { NativeLibrary.cancelBindingCapture() }
            // Once the capture is gone, as it would swallow the releases too.
            controllers?.releaseAll()
        }
    }

    /** Leaves [name] unbound on the selected controller. */
    fun clearBinding(name: String) {
        val descriptor = controllersState.selected ?: return
        if (capturingInput) return
        setBindingShown(name, null)
        readBindings(descriptor) { NativeLibrary.setPadBinding(descriptor, name, null) }
    }

    /** Gives the selected controller its default bindings again. */
    fun resetBindings() {
        val descriptor = controllersState.selected ?: return
        if (capturingInput) return
        readBindings(descriptor) { NativeLibrary.resetPadBindings(descriptor) }
    }

    /** Follows the controllers connected while the Controllers page is open, from no bindings read yet. */
    private fun watchControllers() {
        controllersJob?.cancel()
        controllersState = ControllersState()
        val input = controllers ?: return
        controllersJob = scope.launch { input.connected.collect(::onControllersChanged) }
    }

    /**
     * The page lists [connected], and selects the controller last picked if it's among them, or else the first. A
     * capture on a controller that's gone is cancelled.
     */
    private fun onControllersChanged(connected: List<ConnectedController>) {
        val old = controllersState
        val selected =
            (connected.firstOrNull { it.descriptor == pickedController } ?: connected.firstOrNull())?.descriptor
        if (selected != old.selected) cancelCapture()
        controllersState = controllersState.copy(controllers = connected, selected = selected)
        when {
            selected == null -> {
                bindingsRead++
                controllersState = controllersState.copy(bindings = emptyList())
            }
            selected != old.selected -> readBindings(selected)
        }
    }

    /**
     * Runs [change], if any, then reads [descriptor]'s bindings, on [coreDispatcher]; they show if it's still selected
     * and no later read was asked for.
     */
    private fun readBindings(
        descriptor: String,
        change: (() -> Unit)? = null,
    ) {
        val read = ++bindingsRead
        scope.launch {
            val loaded =
                withContext(coreDispatcher) {
                    change?.invoke()
                    NativeLibrary.getPadBindings(descriptor)?.toList()
                }
            if (read != bindingsRead || controllersState.selected != descriptor) return@launch
            controllersState = controllersState.copy(bindings = loaded.orEmpty())
        }
    }

    // Shows [name] bound to [binding] now, until the bindings are read again.
    private fun setBindingShown(
        name: String,
        binding: String?,
    ) {
        val bindings = controllersState.bindings ?: return
        controllersState =
            controllersState.copy(bindings = bindings.map { if (it.name == name) it.copy(binding = binding) else it })
    }

    private fun endCapture() {
        captureId++
        captureTimer?.cancel()
        captureTimer = null
        controllersState = controllersState.copy(capturing = null, secondsLeft = 0)
    }

    fun closeFolders() {
        if (screen != Screen.Folders || dataChange != null) return
        cancelDataChange()
        settingsEntry = SettingsEntry.Folders
        screen = Screen.Settings
    }

    // --- The folder settings ---

    /** Picks another BIOS folder, which replaces the old one if it holds any images. */
    fun changeBios() {
        val picker = picker ?: return
        if (biosJob?.isActive == true) return
        biosJob =
            scope.launch {
                val picked = picker.pick(FolderKind.Bios) ?: return@launch
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
        val picker = picker ?: return
        scope.launch {
            val picked = picker.pick(FolderKind.Games) ?: return@launch
            if (refuseGamesFolder(picked)) return@launch
            if (picked !in folders.games) setGamesFolders(folders.games + picked)
        }
    }

    /** Refuses, saying why, and lets go of, a games folder [picked] that overlaps the data folder. */
    private suspend fun refuseGamesFolder(picked: String): Boolean {
        val data = folders.data ?: return false
        if (!DataFolderCheck.overlaps(picked, data)) return false
        release(picked)
        showError(DataFolderCheck.GAMES_OVERLAP_DATA)
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
            updateLibrary()
        }
    }

    /** Picks a new data folder, then waits for the user's choice of what to do with it ([changeDataFolder]). */
    fun pickDataFolder() {
        val picker = picker ?: return
        if (dataChange != null) return
        scope.launch {
            val picked = picker.pick(FolderKind.Data) ?: return@launch
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
     * its mirror then stops for good ([DataFolderChange.switchFrom]). Using the new folder's data waits for the user
     * again if some changes didn't reach the old folder, unless the choice was already shown with the warning that
     * they're lost. If the change fails, nothing is saved, and the old folder carries on.
     */
    fun changeDataFolder(choice: DataFolderChoice) {
        val candidate = dataCandidate ?: return
        val usable = candidate.inspection is DataFolderInspection.Usable
        if (!usable || screen != Screen.Folders || dataChange != null) return
        // A game left a moment ago may still be stopping, and the start may still be pulling the old folder. Until the
        // core has started, the layout is only the defaults: a push to the old folder would leave out the folders its
        // settings.ini names, and still count as complete.
        if (gameActive || coldStarting || !dataLayout.fromCore) {
            showMessage("GooseStation is busy. Try again in a moment.")
            return
        }
        val old = folders.data
        // What the screen said when the user chose: with the old folder unreachable, that its missing changes are lost.
        val lossAccepted = !dataReachable(mirrorState.value)
        dataChange = "Saving your latest changes to the old data folder…"
        scope.launch {
            val failure =
                try {
                    val store = SafFolderStore(context.contentResolver, candidate.uri)
                    val applied =
                        DataFolderChange(workingCopy, manifestFile, dataLayout).switchFrom(
                            mirror,
                            pushOld = dataGranted,
                            lossAccepted = lossAccepted,
                            newTree = candidate.uri,
                            choice = choice,
                            store = store,
                        ) {
                            dataChange =
                                when (choice) {
                                    DataFolderChoice.CopyThisDevicesData ->
                                        "Copying this device's data into the new folder…"
                                    DataFolderChoice.UseFolderData -> "Copying the data from the new folder…"
                                }
                            // A settings reload or folder change queued before the mirror closed would have the core
                            // save its settings.ini over the one the change brings in.
                            withContext(coreDispatcher) {}
                        }
                    if (!applied) {
                        oldFolderMissedChanges = true
                        dataChange = null
                        showError(
                            "Not every change on this device reached your old data folder. Choose again to use the " +
                                "new folder's data anyway, or cancel.",
                        )
                        return@launch
                    }
                    folders = folders.copy(data = candidate.uri)
                    if (save()) null else "can't save your folders"
                } catch (e: Exception) {
                    Log.e(TAG, "Can't change the data folder.", e)
                    e.message ?: e.javaClass.name
                }
            if (failure != null) {
                abandonDataChange(old, candidate.uri, failure)
                return@launch
            }
            old?.let { release(it) }
            dataChange = "Restarting…"
            Log.i(TAG, "The data folder changed: restarting.")
            activity?.let(ProcessRestart::restart) ?: exitProcess(0)
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
        showError("Can't change the data folder ($error). The old one is still your data folder.")
        old ?: return
        useMirror(old)
        // The working copy may be part-way changed: a full pull makes it match the old folder again, and reloads
        // settings.ini if it came back.
        pull(onlyMemcards = false)
    }

    /**
     * Offers [uri] as the new data folder, once it's looked at, unless it's the data folder already. One that can't be
     * the data folder ([DataFolderCheck]) is offered only to say why.
     */
    private suspend fun offerDataFolder(uri: String) {
        if (uri == folders.data) {
            showMessage("That's your data folder already.")
            return
        }
        showMessage("Looking at the folder…")
        val inspection = inspectDataFolder(uri, folders.games)
        if (dataChange != null) return
        dataCandidate?.let { if (it.uri != uri) release(it.uri) }
        oldFolderMissedChanges = false
        dataCandidate = DataFolderCandidate(uri, inspection)
        if (message == "Looking at the folder…") message = null
    }

    /** The data folder's state, for the folder settings. */
    fun dataStatus(state: MirrorState): String = LibraryBanners.dataStatus(state, dataGranted)

    /** The data folder holds the latest changes, as far as the app knows. */
    fun dataReachable(state: MirrorState): Boolean =
        dataGranted && state !is MirrorState.Unreachable && !oldFolderMissedChanges

    /** Imports [uri]'s BIOS images, and makes it the BIOS folder if it has any; otherwise nothing changes. */
    private suspend fun useBiosFolder(
        uri: String,
        rescan: Boolean,
    ) {
        showMessage("Looking for BIOS images…")
        val result = importBios(uri)
        if (result.images.isEmpty()) {
            if (uri != folders.bios) release(uri)
            val error = result.error ?: "No PS1 BIOS found in this folder."
            showError(if (rescan) "$error The copies already made still work." else "$error Pick another.")
            return
        }
        message = null
        val old = folders.bios
        if (uri == old) return
        folders = folders.copy(bios = uri)
        save()
        old?.let { release(it) }
        checkGrants()
    }

    // --- Games ---

    fun startGame(game: GameEntry) {
        if (!ready || gameActive) return
        if (!catchUps.startsAllowed) {
            showMessage(COPYING)
            return
        }
        lastGamePath = game.path
        focusSettings = false
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
        promptOpen = false
        screen = Screen.Library
        if (!messageIsError) message = "Stopping the game…"
        NativeLibrary.shutdownGame()
    }

    /**
     * Pauses the game and shows its prompt, from Back, Mode or Select + Start held. Everything the controllers hold is
     * let go first, so nothing pressed now is still pressed after Resume. The pause writes the memory cards. A game
     * still booting is paused once it has booted ([onGameStarted]).
     */
    fun openPrompt() {
        if (screen != Screen.Game || promptOpen) return
        promptOpen = true
        controllers?.releaseAll()
        pauseGame()
    }

    /** Pauses the game, which writes its memory cards: for the prompt, or the app going to the background. */
    fun pauseGame() {
        if (gameActive && !gameRunning) pausedDuringBoot = true
        NativeLibrary.setPaused(true)
    }

    /** Closes the prompt, and carries on with the game if the app is in front ([PausePolicy]). */
    fun resumeFromPrompt() {
        if (!promptOpen) return
        promptOpen = false
        if (gameRunning && PausePolicy.running(resumed, promptOpen = false)) NativeLibrary.setPaused(false)
    }

    /** Closes the prompt, and stops the game. */
    fun quitFromPrompt() {
        if (!promptOpen) return
        promptOpen = false
        leaveGame()
    }

    // --- SD card removal ---

    /**
     * Listens, for the life of the process, for volumes going away (ejected, unmounted or pulled out: the first of
     * these is handled) and coming back. The broadcasts come from the system, so the receiver isn't exported.
     */
    private fun watchVolumes(appContext: Context) {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addDataScheme("file")
            }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val volumeId = intent.data?.path?.let(VolumeMatch::volumeIdOfMountPath) ?: return
                    val mounted = intent.action == Intent.ACTION_MEDIA_MOUNTED
                    Log.i(TAG, "${intent.action} for volume $volumeId")
                    // Off onReceive, on the main thread, where the app's state lives.
                    scope.launch { if (mounted) onVolumeMounted(volumeId) else onVolumeEjected(volumeId) }
                }
            }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * The volume [volumeId] is going away, as [EjectPlan] says. Android kills a process that still has a file open
     * on it about 5 s after the eject starts: a game on it, or one whose data folder is on it, has its memory cards
     * saved to the working copy and is stopped, closing its disc, and the mirror stops using a data folder on it.
     */
    private suspend fun onVolumeEjected(volumeId: String) {
        if (!ejectedVolumes.add(volumeId)) return
        val actions = EjectPlan.of(volumeId, lastGamePath, folders.data, gameActive)
        if (actions.isEmpty()) return
        Log.i(TAG, "Volume $volumeId is going away: $actions")
        if (EjectAction.ShowMessage in actions) showError(GAME_STOPPED_FOR_EJECT)
        if (EjectAction.FlushAndStopGame in actions) {
            // The shutdown saves the memory cards anyway, before it closes the disc. Pausing writes them at once
            // (android_host.cpp), before anything else the core runs. onGameStopped() follows.
            NativeLibrary.setPaused(true)
            if (screen == Screen.Game) screen = Screen.Library
            NativeLibrary.shutdownGame()
        }
        if (EjectAction.StopMirror in actions) mirror?.hold(CARD_REMOVED)
    }

    /**
     * The volume [volumeId] is back: a data folder on it is mirrored again, starting with a push now, so the changes
     * held back go out and the banner goes without waiting for the next tick.
     */
    private suspend fun onVolumeMounted(volumeId: String) {
        if (!ejectedVolumes.remove(volumeId)) return
        val data = folders.data ?: return
        if (!VolumeMatch.treeOnVolume(data, volumeId)) return
        val mirror = mirror ?: return
        mirror.letGo()
        push(force = false)
    }

    // --- The sequences ---

    /** Grants, then the pull, the core's start, its game folders and the library, as [StartupPlan] says. */
    private suspend fun coldStart(saved: SavedFolders) {
        coldStarting = true
        try {
            coldStartSteps(saved)
        } finally {
            coldStarting = false
        }
    }

    private suspend fun coldStartSteps(saved: SavedFolders) {
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
        saved.data?.let(::useMirror)
        for (step in steps) {
            when (step) {
                StartupStep.Pull -> firstPull()
                StartupStep.InitializeCore -> {
                    if (!initializeCore()) return
                    // The folders the core's settings.ini names beyond the defaults, which the first pull left out,
                    // then the cards the game settings in them name: no game starts before they're in.
                    if (!messageIsError) message = COPYING
                    catchUp()?.join()
                    ready = true
                }
                StartupStep.SetGameFolders -> applyGameFolders()
                StartupStep.RefreshLibrary -> refreshLibrary(showProgress = true)
                StartupStep.ShowSetup, StartupStep.ReloadSettings, StartupStep.ReadCorePaths -> {}
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

    /** A mirror of [data], whose state the app shows from now on. */
    private fun useMirror(data: String) {
        mirrorStateJob?.cancel()
        val store = SafFolderStore(context.contentResolver, data)
        val created =
            Mirror(
                store,
                workingCopy,
                manifestFile,
                System::currentTimeMillis,
                removeStrays = { layoutSettling.settled },
            ) { dataLayout }
        mirror = created
        mirrorStateJob = scope.launch { created.state.collect { mutableMirrorState.value = it } }
    }

    // The first pull, before the core reads settings.ini, with its progress shown.
    private suspend fun firstPull() {
        val mirror = mirror ?: return
        prepareWorkingCopy()
        message = COPYING
        val progress =
            scope.launch {
                mirror.state.collect { state ->
                    // done counts the actions finished, so the one under way is done + 1.
                    if (state is MirrorState.Syncing) message = "$COPYING (${state.done + 1} of ${state.total})"
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
        withContext(Dispatchers.IO) {
            if (workingCopy.isDirectory) return@withContext
            if (manifestFile.delete()) Log.w(TAG, "The working copy is gone: pulling the data folder in full.")
            if (!workingCopy.mkdirs()) Log.e(TAG, "Can't create $workingCopy")
        }

    /**
     * A pull of everything but the running game's memory cards (only the memory cards when [onlyMemcards]), then what
     * [StartupPlan.afterPull] says, and a [catchUp] if that grew the layout, however it ends
     * ([CatchUpRunner.guardGrowth]). Only once the core runs, so never before the first pull, and never without the
     * data grant. Returns whether that refreshed the library.
     */
    private suspend fun pull(onlyMemcards: Boolean): Boolean =
        catchUps.guardGrowth(::catchUp) { onGrowth -> pullOnce(onlyMemcards, onGrowth).refreshed }

    /**
     * A catch-up's pull ([CatchUp.run]): as [pull], but returns whether it grew the layout, for the next one. Its
     * growth isn't flagged: the run refuses starts while it lasts.
     */
    private suspend fun pullGrew(): Boolean {
        val grew = pullOnce(onlyMemcards = false, onGrowth = {}).layoutGrew
        Log.i(TAG, "Catch-up pull: the data layout ${if (grew) "grew" else "stopped growing"}.")
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
        if (!coreStarted || !dataGranted) return Applied.NOTHING
        val pull = layoutSettling.pullStarting()
        // Asked for each file as the mirror acts, so a game started meanwhile keeps its cards.
        val result =
            mirror.pull { path ->
                if (onlyMemcards) {
                    !MemoryCardPaths.matches(path, memcardPaths)
                } else {
                    MemoryCardPaths.deferred(path, memcardPaths, holdMemcards)
                }
            }
        val applied = applyChanged(result.changed, onGrowth)
        // Only the memory cards: game settings that name more files may be among those it left.
        if (!onlyMemcards) layoutSettling.pulled(pull, result.complete)
        return applied
    }

    // As pull(): a push can copy files in as well, where the folder's side changed too. The ticker's is cancelled as
    // the app goes to the background, maybe just after it grew the layout.
    private suspend fun push(force: Boolean) {
        val mirror = mirror ?: return
        if (!coreStarted || !dataGranted) return
        catchUps.guardGrowth(::catchUp) { onGrowth ->
            val result = mirror.push(force) { MemoryCardPaths.deferred(it, memcardPaths, holdMemcards) }
            applyChanged(result.changed, onGrowth)
        }
    }

    /**
     * Pulls until the layout stops growing ([CatchUp]): the folders and cards that new settings.ini or game settings
     * name, which the run that brought them left out. Until it's done no game starts ([CatchUpRunner]), and strays
     * stay until a complete pull settles the layout ([layoutSettling]), maybe after it's done. One under way pulls
     * again once it's done instead, from the layout as it is then: its job is returned. Main thread only.
     */
    private fun catchUp(): Job? {
        layoutSettling.unsettle()
        if (!catchUps.request()) return catchUpJob
        return scope
            .launch {
                try {
                    catchUps.run { pullGrew() }
                } finally {
                    if (message == COPYING) message = null
                }
            }.also { catchUpJob = it }
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
        val readable = readableGameFolders()
        var layoutGrew = false
        val refreshed =
            withContext(coreDispatcher) {
                layoutGrew =
                    StartupPlan.carryOut(steps, onGrowth) { step ->
                        when (step) {
                            StartupStep.ReloadSettings -> {
                                NativeLibrary.reloadSettings()
                                readGridView()
                                readCorePaths()
                            }
                            StartupStep.ReadCorePaths -> readCorePaths()
                            StartupStep.SetGameFolders -> false.also { NativeLibrary.setGameFolders(readable) }
                            StartupStep.RefreshLibrary -> false.also { NativeLibrary.refreshGameList(false) }
                            else -> false
                        }
                    }
                if (StartupStep.RefreshLibrary in steps) NativeLibrary.getGameList().toList() else null
            }
        refreshed?.let { games = it }
        return Applied(refreshed != null, layoutGrew)
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
                    ).also {
                        if (it) {
                            readGridView()
                            readCorePaths()
                        }
                    }
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
        if (biosOverridesToCheck) dropStaleBiosOverrides()
        // Reported once the core takes input; it keeps connections made before then anyway.
        controllers =
            ControllerInput(context).also {
                it.onMenuRequested = ::openPrompt
                it.start()
            }
        return true
    }

    /**
     * Sets each region whose image filesDir/bios no longer has back to Automatic, then has the core apply that, on
     * [coreDispatcher]. Only once the core has started: it reads and writes the core's settings.ini.
     */
    private suspend fun dropStaleBiosOverrides() {
        biosOverridesToCheck = false
        withContext(coreDispatcher) {
            val copies = File(context.filesDir, "bios").list()?.toList().orEmpty()
            val stale = BiosOverrides.stale(BiosOverrides.load(NativeCoreSettings), copies)
            if (stale.isEmpty()) return@withContext
            Log.i(TAG, "Back to Automatic, as the BIOS folder no longer has their image: $stale")
            stale.forEach { BiosOverrides.set(NativeCoreSettings, it, null) }
            NativeLibrary.applySettings()
        }
    }

    // On coreDispatcher, once the core has read settings.ini.
    private suspend fun readGridView() {
        val grid = NativeLibrary.getBoolSetting(UI, GRID_VIEW, true)
        withContext(Dispatchers.Main) { gridView = grid }
    }

    /**
     * The memory card paths and the [dataLayout], as the core has them; returns whether the layout gained a folder or
     * file, which unsettles it ([layoutSettling]). On coreDispatcher, once the core has read settings.ini.
     */
    private fun readCorePaths(): Boolean {
        val reported = NativeLibrary.getMemoryCardPaths()?.toList() ?: MemoryCardPaths.DEFAULT
        memcardPaths = MemoryCardPaths.merge(reported, memcardPaths)
        val folders = NativeLibrary.getDataFolders() ?: return false
        val layout = DataLayout.withCorePaths(folders.toList() + memcardPaths, NativeLibrary.getCacheFolder())
        val grew = layout.addsTo(dataLayout)
        // Before a mirror run can start with it.
        if (grew) layoutSettling.unsettle()
        dataLayout = layout
        return grew
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

    /** Saves [folders], committed before it returns; false, with the error shown, if that failed. */
    private suspend fun save(): Boolean {
        val saved = folders
        val written = withContext(prefsDispatcher) { prefs.save(saved) }
        if (!written) showError("Can't save your folders.")
        return written
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
            // Booted in the background or with the prompt open, it waits paused. One the app paused while it booted
            // runs if it may by now, as that pause may have reached the core just after the boot. One the user left
            // while it booted is shut down next anyway.
            val paused = PausePolicy.atBoot(pausedDuringBoot, PausePolicy.running(resumed, promptOpen))
            pausedDuringBoot = false
            if (screen == Screen.Game && paused != null) NativeLibrary.setPaused(paused)
            // The cards its own settings name are held back from now on, and mirrored.
            scope.launch { withContext(coreDispatcher) { readCorePaths() } }
        }
    }

    override fun onGameStopped() {
        mainHandler.post {
            Log.i(TAG, "Game stopped.")
            gameRunning = false
            pausedDuringBoot = false
            // Same as on start: release in order with this thread's input.
            controllers?.releaseAll()
            promptOpen = false
            gameActive = false
            holdMemcards = false
            if (screen == Screen.Game) screen = Screen.Library
            if (!messageIsError) message = null
            // The memory cards held back while it ran come in now, then the game's own writes go out.
            scope.launch {
                withContext(coreDispatcher) { readCorePaths() }
                pull(onlyMemcards = true)
                push(force = true)
            }
        }
    }

    override fun onBindingCaptured(binding: String) {
        // The capture's, as the core reports it: a later one only starts once this one can't report any more.
        val id = nativeCaptureId
        mainHandler.post {
            val state = controllersState
            val name = state.capturing
            val descriptor = state.selected
            // Cancelled, timed out or replaced since it was posted.
            if (id != captureId || name == null || descriptor == null) return@post
            Log.i(TAG, "Captured $binding for $name")
            endCapture()
            // The core removed the capture as it took the input, so the releases reach it.
            controllers?.releaseAll()
            setBindingShown(name, binding)
            readBindings(descriptor) { NativeLibrary.setPadBinding(descriptor, name, binding) }
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
