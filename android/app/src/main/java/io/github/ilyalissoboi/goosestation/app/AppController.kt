// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import io.github.ilyalissoboi.emusync.SyncSettings
import io.github.ilyalissoboi.goosestation.BuildConfig
import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.GameAchievements
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.LeaderboardPage
import io.github.ilyalissoboi.goosestation.core.NativeCallbacks
import io.github.ilyalissoboi.goosestation.core.NativeCoverApi
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import io.github.ilyalissoboi.goosestation.core.NativeShaderPackApi
import io.github.ilyalissoboi.goosestation.core.Resources
import io.github.ilyalissoboi.goosestation.input.ControllerInput
import io.github.ilyalissoboi.goosestation.input.PadKind
import io.github.ilyalissoboi.goosestation.input.Rumble
import io.github.ilyalissoboi.goosestation.library.LibraryPrefs
import io.github.ilyalissoboi.goosestation.library.LibrarySort
import io.github.ilyalissoboi.goosestation.library.LibrarySortState
import io.github.ilyalissoboi.goosestation.mirror.DataFolderChange
import io.github.ilyalissoboi.goosestation.mirror.DataFolderChoice
import io.github.ilyalissoboi.goosestation.mirror.Mirror
import io.github.ilyalissoboi.goosestation.mirror.MirrorState
import io.github.ilyalissoboi.goosestation.mirror.SafFolderStore
import io.github.ilyalissoboi.goosestation.settings.BiosOverrides
import io.github.ilyalissoboi.goosestation.settings.GameCoreSettings
import io.github.ilyalissoboi.goosestation.settings.InterfaceSettings
import io.github.ilyalissoboi.goosestation.settings.NativeCoreSettings
import io.github.ilyalissoboi.goosestation.storage.BiosImportResult
import io.github.ilyalissoboi.goosestation.storage.BiosImporter
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import io.github.ilyalissoboi.goosestation.storage.CurrentDocuments
import io.github.ilyalissoboi.goosestation.storage.DataFolderCheck
import io.github.ilyalissoboi.goosestation.storage.DataFolderInspection
import io.github.ilyalissoboi.goosestation.storage.DocumentPicker
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
import io.github.ilyalissoboi.goosestation.update.ApkCheck
import io.github.ilyalissoboi.goosestation.update.Installer
import io.github.ilyalissoboi.goosestation.update.UpdatePrefs
import io.github.ilyalissoboi.goosestation.update.UpdateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

/**
 * The core, the folders, the mirror and what the app shows, for the life of the process, so a recreated activity
 * carries on: the wiring between the pieces, the activity's lifecycle and the cold start. Main thread only. The core's
 * callbacks are [CoreEvents]: it posts each to the main thread, and only the few the core needs done before it goes on
 * ([CoreThreadEvents]) run on the core's threads, none of them touching what the main thread owns.
 *
 * The work behind it runs off the main thread: preferences, grants and SAF on [Dispatchers.IO], the mirror on its
 * own thread, and the core's library and settings calls on [coreDispatcher], one at a time.
 */
object AppController {
    private const val TAG = "GooseStation"

    /** An achievement message's picture, as big as the message's line shows it. */
    private const val ICON_PX = 64
    private const val PUSH_INTERVAL_MS = 2_000L
    private const val UI = "UI"
    private const val GRID_VIEW = "GameListGridView"
    private const val CARD_REMOVED = "the SD card was removed"
    private const val GAME_STOPPED_FOR_EJECT =
        "The SD card was removed, so the game was stopped. Its memory cards are saved on this device."
    private const val STARTING = "Starting…"

    /** A game the in-game menu left for, from its shared slot, that the library no longer has. */
    private const val GONE_FROM_LIBRARY = "Couldn't start that save's game: it isn't in your library any more."

    private val mainHandler = Handler(Looper.getMainLooper())

    // The controllers' rumble, on a thread of its own, so neither the core nor the UI waits on a vibrator.
    private val rumble by lazy {
        val thread = HandlerThread("Rumble").apply { start() }
        val handler = Handler(thread.looper)
        Rumble(motorsFor = { controllers?.motorsFor(it) }, post = { handler.post(it) })
    }
    private val scope = MainScope()

    // refreshGameList and reloadSettings never overlap: reloadSettings rewrites the folder paths a scan reads.
    private val coreDispatcher = Dispatchers.IO.limitedParallelism(1)

    // The settings work on the core queue: the page models' and this class's own reads and writes. A game's start
    // waits for it, so it boots on the settings asked for; the queue's scans and covers aren't counted.
    private val settingsQueue = SettingsQueue(coreDispatcher)

    // The in-game menu's core calls (GameCoreApi), one at a time, apart from coreDispatcher: a library scan running
    // there never holds up the menu.
    private val gameDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Saves in the order they're made, so an older one never lands last.
    private val prefsDispatcher = Dispatchers.IO.limitedParallelism(1)

    private lateinit var context: Context
    private lateinit var prefs: FolderPrefs
    private lateinit var biosImporter: BiosImporter
    private var started = false
    private val currentActivity = CurrentActivity<Activity>()
    private val activity get() = currentActivity.current
    private var picker: FolderPicker? = null
    private var documentPicker: DocumentPicker? = null
    private var pushTicker: Job? = null
    private var refreshJob: Job? = null

    /** The volumes being ejected, or gone, since their eject: each is handled once, until it's mounted again. */
    private val ejectedVolumes = HashSet<String>()

    /** The saved folders, as last loaded or saved ([FolderSettings.folders]). */
    val folders get() = folderSettings.folders

    /** The BIOS images the last import copied, as it described them. */
    val biosImages get() = folderSettings.biosImages

    /** A folder picked to become the data folder, waiting for the user's choice in the folder settings. */
    val dataCandidate get() = folderSettings.dataCandidate

    /** What a data folder change under way is doing; null when none is. It ends in a restart. */
    val dataChange get() = folderSettings.dataChange

    /** The library focuses its Settings button when it opens ([Navigator.focusSettings]). */
    val focusSettings get() = navigator.focusSettings

    /** The update check and an update under way; made in [start]. */
    var updates by mutableStateOf<Updates?>(null)
        private set

    /** The category of the page last left, which the settings focus ([Navigator.settingsEntry]). */
    val settingsEntry get() = navigator.settingsEntry

    /** In the settings' two panels, the focus is in the open page ([Navigator.settingsInPage]). */
    val settingsInPage get() = navigator.settingsInPage

    /** The Graphics and BIOS pages' values. */
    val settingsPages = SettingsPages(scope, settingsQueue, NativeCoreApi, NativeCoreSettings)

    // Where the shader browser opens, kept in this device's preferences (read once they're there).
    private val shaderFolder =
        object : ShaderFolderMemory {
            override fun get() = if (::prefs.isInitialized) prefs.shaderBrowserFolder() else ""

            override fun set(folder: String) {
                if (::prefs.isInitialized) prefs.setShaderBrowserFolder(folder)
            }
        }

    /** The Post-processing pages' chains, settings.ini's. */
    val postProcessingPage =
        PostProcessingPage(
            scope,
            settingsQueue,
            NativeCoreApi,
            NativeCoreSettings,
            shaderFolder,
        ) { GameCoreSettings(it) }

    /** A game's own settings, from its tile in the library. */
    val gameSettingsPages =
        GameSettingsPages(
            scope,
            settingsQueue,
            NativeCoreApi,
            NativeCoreSettings,
            shaderFolder,
        ) { GameCoreSettings(it) }

    /**
     * The running game's own settings, from its menu: on the menu's own dispatcher, as its other calls are, so a
     * library scan never holds them up.
     */
    val gameMenuSettings =
        GameSettingsPages(
            scope,
            gameDispatcher,
            NativeCoreApi,
            NativeCoreSettings,
            shaderFolder,
        ) { GameCoreSettings(it) }

    /**
     * The shader packs, from each Post-processing page: the app's, so an install goes on when its page closes. A job
     * that changed the packs has the pages that show read their shaders again.
     */
    val shaderPacks =
        ShaderPacks(scope, Dispatchers.IO, NativeShaderPackApi) {
            listOf(postProcessingPage, gameSettingsPages.postProcessing, gameMenuSettings.postProcessing)
                .filter { it.chains.isNotEmpty() }
                .forEach { it.reread() }
        }

    /**
     * The Covers page's sources and download: the app's, so a download goes on when the page closes, until a game
     * starts or the app is hidden. Each cover found goes straight onto its tile; once a download that found some
     * ends, the library's tiles are read again (not rescanned), for any other tile the same cover file serves.
     */
    val covers =
        Covers(
            scope,
            Dispatchers.IO,
            coreDispatcher,
            NativeCoverApi,
            NativeCoreSettings,
            onFinished = { if (it.found > 0) scope.launch { rereadLibrary() } },
        ) { gamePath, coverPath ->
            games = games.map { if (it.path == gamePath) it.copy(coverPath = coverPath) else it }
        }

    // A sign-in waits for the server: on a queue of its own, so the pages don't wait behind it.
    private val signInDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** The Achievements page: the RetroAchievements account, signing in and out, and its switches. */
    val achievementsPage = AchievementsPage(scope, settingsQueue, signInDispatcher, NativeCoreApi, NativeCoreSettings)

    /** Memory card sync's state, for the Memory cards page and the library's badge: read again as sync says it changed. */
    val memcardSyncStatus = MemcardSyncStatus(scope, Dispatchers.IO, NativeCoreApi)

    val memcardSyncPage by lazy {
        MemcardSyncPage(
            scope,
            settingsQueue,
            Dispatchers.IO,
            NativeCoreApi,
            NativeCoreSettings,
            syncSecrets,
            memcardSyncStatus,
        )
    }

    // The file picker of the activity shown, for the memory card editor: nothing is picked without one.
    private val documents = CurrentDocuments { documentPicker }

    /**
     * The memory card editor, its edits kept in memory while the process lives. Each Save is pushed to the data folder
     * at once, as a game's writes are when it stops.
     */
    val memcardEditor by lazy {
        MemcardEditor(
            scope,
            coreDispatcher,
            Dispatchers.IO,
            NativeMemcardEditorApi,
            documents,
            syncOn = { memcardSyncStatus.state?.enabled == true },
            onSaved = { scope.launch { dataSync.push(force = true) } },
        )
    }

    /** The Controllers page's controllers, bindings and capture. */
    val controllersPage =
        ControllersPage(scope, settingsQueue, NativeCoreApi, {
            controllers
        }, { screen == Screen.Controllers }, NativeCoreSettings) {
            Log.i(TAG, it)
        }

    /** A capture runs: the controllers' input goes to the core, which takes the next one as a binding. */
    val capturingInput: Boolean get() = controllersPage.capturingInput

    /** An import copied images before the core started: the overrides they leave stale are dropped once it has. */
    private var biosOverridesToCheck = false

    /** initialize() succeeded: from then on, starts are resumes. Read off the main thread too (NetworkWatch). */
    @Volatile private var coreStarted = false

    /** The cold-start sequence is under way: the data folder can't change until it's done. */
    private var coldStarting = false

    // What the screens' moves ask of the pages, or tell them ([Navigator]).
    private val screenPages =
        object : ScreenPages {
            override val ready get() = this@AppController.ready

            override val gameActive get() = this@AppController.gameActive

            override val hasConflicts get() = SyncBadges.of(memcardSyncStatus.state) is SyncBadge.Conflicts

            override fun loadPage(entry: SettingsEntry) {
                CorePage.of(entry)?.let(settingsPages::load)
                when (entry) {
                    // Read as their CorePage, above.
                    SettingsEntry.Interface,
                    SettingsEntry.Console,
                    SettingsEntry.Emulation,
                    SettingsEntry.Graphics,
                    SettingsEntry.Audio,
                    SettingsEntry.Advanced,
                    -> {}
                    SettingsEntry.Achievements -> achievementsPage.load()
                    SettingsEntry.MemoryCards -> {
                        memcardSyncStatus.refresh()
                        memcardEditor.loadCount(games)
                    }
                    SettingsEntry.Bios -> settingsPages.loadBios()
                    SettingsEntry.Controllers -> controllersPage.open()
                    SettingsEntry.PostProcessing -> postProcessingPage.open(null, running = false)
                    SettingsEntry.Folders -> {}
                    SettingsEntry.Covers -> covers.open()
                }
            }

            override fun closePage(entry: SettingsEntry): Boolean {
                when (entry) {
                    SettingsEntry.Folders -> {
                        if (dataChange != null) return false
                        cancelDataChange()
                    }
                    SettingsEntry.Controllers -> controllersPage.close()
                    SettingsEntry.PostProcessing -> postProcessingPage.close()
                    SettingsEntry.Covers -> covers.close()
                    // Log to file adds the logs to the mirror, or takes them off: after the page's saves, queued before
                    // this.
                    SettingsEntry.Advanced -> scope.launch { withContext(coreDispatcher) { dataSync.readCorePaths() } }
                    else -> {}
                }
                return true
            }

            override fun openSaves(game: GameEntry) = savesPage.open(game)

            override fun closeSaves(): String? {
                val path = savesPage.state?.game?.path
                savesPage.close()
                return path
            }

            override fun openGameSettings(game: GameEntry) = gameSettingsPages.open(game, running = false)

            override fun closeGameSettings(): String? {
                val path = gameSettingsPages.game?.path
                gameSettingsPages.close()
                return path
            }

            override fun openMemcardEditor() = memcardEditor.open(games)

            override fun memcardEditorClosed() = memcardEditor.loadCount(games)

            override fun openSyncConflicts() {
                memcardSyncPage.loadConflicts()
                memcardSyncStatus.refresh()
            }

            override fun syncConflictsClosed() = memcardSyncStatus.refresh()

            override fun savesLeftForGame() = savesPage.close()
        }

    /** The screen shown, and the moves between screens ([Navigator]). */
    private val navigator: Navigator = Navigator(screenPages)

    /** The screen shown ([Navigator.screen]). */
    val screen get() = navigator.screen

    /** The in-game menu shows over the game ([GameSession.menuOpen]). */
    val menuOpen get() = session.menuOpen

    /** Saved folders whose grant is gone, as the last check found. */
    val missingGrants get() = folderSettings.missingGrants

    val mirrorState: StateFlow<MirrorState> get() = dataSync.mirrorState

    /** The game list, as the last refresh found it. */
    var games by mutableStateOf(emptyList<GameEntry>())
        private set

    /** The library shows a cover grid, or else a list: `[UI] GameListGridView`, read once the core runs. */
    var gridView by mutableStateOf(true)
        private set

    /** What the library's search field holds, kept while the player visits other screens; cleared as a game starts. */
    val librarySearch = TextFieldState()

    // The library's sort, in Big Picture's keys (LibraryPrefs), read as the core starts.
    private val librarySorts =
        LibrarySortState { sort -> scope.launch(settingsQueue) { LibraryPrefs.write(NativeCoreSettings, sort) } }

    /** The library's sort: settings.ini's, or the player's latest pick, which a pick before the core starts keeps. */
    val librarySort: LibrarySort get() = librarySorts.sort

    /** The last game started, which the library focuses on coming back to it. */
    val lastGamePath get() = session.lastGamePath

    /** The core is initialized, and the data folder pulled in full: games can start, and the game screen can show. */
    var ready by mutableStateOf(false)
        private set

    // The latest error, or what the app is doing; one at a time, and each line its owner's to clear.
    private val status = StatusLine(Status.Starting, STARTING)

    /** The latest error, or what the app is doing; one at a time. */
    val message get() = status.text
    val messageIsError get() = status.isError

    /** A game from a start isn't over yet: until the core says it stopped ([GameSession.gameActive]). */
    val gameActive get() = session.gameActive

    // What a game's session asks the app, or tells it ([GameSession]).
    private val sessionHooks: SessionHooks =
        object : SessionHooks {
            override val ready get() = this@AppController.ready

            override val startsAllowed get() = dataSync.startsAllowed

            override val dataChanging get() = dataChange != null

            // The session only ever moves to the game, or back to the library.
            override var screen: Screen
                get() = navigator.screen
                set(value) {
                    when (value) {
                        Screen.Game -> navigator.showGame()
                        Screen.Library -> navigator.showLibrary()
                        else -> error("A game's session doesn't show $value.")
                    }
                }

            override fun keepsCopyingLine() = dataSync.keepsLine(status.shows(Status.Copying))

            override fun onBegin(game: GameEntry) {
                // The search is for finding a game: once one starts, the library comes back whole, the sort kept.
                librarySearch.clearText()
                dataSync.holdMemcards = true
                // A cover download stops for the game, keeping the covers it found.
                covers.stop()
                navigator.gameBegins()
            }

            override fun onStarted(game: GameEntry) {
                gameMenu.gameStarted(game)
                achievementMessages.gameStarted()
                touchControls.gameStarted()
                readSelectChords()
            }

            override fun onStopped() {
                dataSync.holdMemcards = false
                osd.clear()
                achievementOverlay.clear()
                gameMenu.gameStopped()
                controllers?.selectChordDescriptors = emptySet()
                rumble.stopAll()
            }

            override fun menuShown(open: Boolean) = achievementOverlay.menuOpen(open)

            override fun menuOpened() = gameMenu.opened()

            override fun releaseInput() {
                controllers?.releaseAll()
            }

            override fun releaseTouch() = touchControls.release()

            override fun stopRumble() = rumble.stopAll()

            override fun say(text: String) {
                osd.post(text)
            }

            override fun readCorePaths() {
                scope.launch { withContext(coreDispatcher) { dataSync.readCorePaths() } }
            }

            override suspend fun syncAfterStop() {
                withContext(coreDispatcher) { dataSync.readCorePaths() }
                dataSync.pull(onlyMemcards = true)
                dataSync.push(force = true)
            }

            override fun startNext(next: StateStart) = this@AppController.startNext(next)
        }

    /** The game: its start, its pauses, its menu and its stop ([GameSession]). */
    private val session: GameSession =
        GameSession(scope, { settingsQueue.settle() }, NativeSessionCore, sessionHooks, status)

    // What the folder settings ask of Android ([FolderSettings]): the picker of the activity shown, if there is one.
    private val folderAccess =
        object : FolderAccess {
            override suspend fun pick(kind: FolderKind) = picker?.pick(kind)

            override fun release(
                uri: String,
                saved: SavedFolders,
            ) {
                picker?.release(uri, saved)
            }

            override suspend fun importBios(treeUri: String) = biosImporter.import(treeUri)

            override fun inspectDataFolder(
                treeUri: String,
                games: List<String>,
            ) = DataFolderCheck.inspect(context.contentResolver, treeUri, games)

            override fun missingGrants(saved: SavedFolders) =
                GrantCheck.missing(saved, context.contentResolver.persistedGrants())
        }

    // The data folder's move to another folder, from the mirror running now.
    private val dataFolderSwitch =
        DataFolderSwitch { newTree, choice, pushOld, lossAccepted, beforeApply ->
            val store = SafFolderStore(context.contentResolver, newTree)
            val change = DataFolderChange(workingCopy, manifestFile, dataSync.dataLayout, dataSync::packMarkers)
            change.switchFrom(dataSync.mirror, pushOld, lossAccepted, newTree, choice, store, beforeApply)
        }

    // What the folder settings ask the app, or tell it ([FolderSettings]).
    private val folderHooks =
        object : FolderHooks {
            override val onFolders get() = screen == Screen.Folders

            override val onLibrary get() = screen == Screen.Library

            // A game left a moment ago may still be stopping, and the start may still be pulling the old folder. Until
            // the core has started, the layout is only the defaults.
            override val busy get() = gameActive || coldStarting || !dataSync.dataLayout.fromCore

            override val mirrorState get() = this@AppController.mirrorState.value

            override suspend fun biosImagesChanged() {
                if (coreStarted) dropStaleBiosOverrides() else biosOverridesToCheck = true
            }

            override fun reloadShownPage() = settingsPages.reloadShown(screen.page)

            override suspend fun updateLibrary() = this@AppController.updateLibrary()

            override fun showFolders() = navigator.showFolders()

            override suspend fun pullAll() {
                dataSync.pull(onlyMemcards = false)
            }

            override fun useMirror(data: String) = dataSync.useMirror(data)

            override suspend fun settleCore() = withContext(coreDispatcher) {}

            override fun restart() {
                activity?.let(ProcessRestart::restart) ?: exitProcess(0)
            }
        }

    /**
     * The saved folders, their grants and what changes them ([FolderSettings]). Built once [start] has the context and
     * the preferences.
     */
    private val folderSettings: FolderSettings by lazy {
        FolderSettings(
            scope,
            Dispatchers.IO,
            prefsDispatcher,
            prefs,
            folderAccess,
            dataFolderSwitch,
            folderHooks,
            status,
        ) { priority, line, error ->
            Log.println(priority, TAG, if (error == null) line else "$line\n${Log.getStackTraceString(error)}")
        }
    }

    // What the mirror's runs tell the app, or ask it ([DataSync]).
    private val syncHooks =
        object : SyncHooks {
            override fun coreStarted() = coreStarted

            override fun dataGranted() = folderSettings.dataGranted

            override fun readableGameFolders() = folderSettings.readableGameFolders()

            override suspend fun onSettingsReloadedOnCore() = readLibraryView()

            override fun onLibraryRead(games: List<GameEntry>) {
                this@AppController.games = games
            }

            override fun onSettingsReloaded(steps: List<StartupStep>) {
                if (StartupStep.ReloadSettings in steps) settingsPages.reloadShown(screen.page)
                if (StartupStep.ReloadSettings in steps && screen == Screen.PostProcessing) postProcessingPage.reread()
                if (StartupStep.ReloadSettings in steps) controllersPage.reloadShown()
                // settings.ini, or a game's own file, pulled from the data folder while that game's pages show.
                if (StartupStep.ReloadSettings in steps || StartupStep.ReadCorePaths in steps) {
                    gameSettingsPages.reloadShown()
                    gameMenuSettings.reloadShown()
                }
            }

            override fun refreshEditor() = this@AppController.refreshEditor()

            override fun showCopying(progress: Pair<Int, Int>?) {
                if (progress == null) {
                    status.progress(Status.Copying, COPYING)
                } else {
                    status.progress(Status.CopyingProgress, "$COPYING (${progress.first} of ${progress.second})")
                }
            }

            override fun copyingShown() = status.shows(Status.Copying)

            override fun clearCopying() = status.clear(Status.Copying)
        }

    /** The data folder mirror's runs, and the state they decide by ([DataSync]). Built once [start] has the context. */
    private val dataSync by lazy {
        DataSync(
            scope,
            coreDispatcher,
            Dispatchers.IO,
            NativeSyncCore,
            syncHooks,
            workingCopy,
            manifestFile,
            SharedPreferencesStore(context, "memcard_sync"),
            makeMirror = { data, guard, packMarkers, removeStrays, layout ->
                Mirror(
                    SafFolderStore(context.contentResolver, data),
                    workingCopy,
                    manifestFile,
                    System::currentTimeMillis,
                    removeStrays = removeStrays,
                    guard = guard,
                    packMarkers = packMarkers,
                    layout = layout,
                )
            },
        ) { priority, line -> Log.println(priority, TAG, line) }
    }

    /** The messages shown over the game, the app's and the core's ([CoreMessages]). */
    val osd = OsdMessages(SystemClock::uptimeMillis)

    /** Achievement badges and game icons from the core's cache, as the menu and the game screen show them. */
    val badges = BadgeImages(scope, Dispatchers.IO)

    /** RetroAchievements' unlock cards and live indicators over the game. */
    val achievementOverlay = AchievementOverlay(SystemClock::uptimeMillis)

    private val achievementSounds by lazy { AchievementSounds() }

    // A network coming back sends the achievements waiting, in the library or in a game.
    private val networkWatch by lazy {
        NetworkWatch(context) {
            if (coreStarted) scope.launch(coreDispatcher) { NativeLibrary.achievementsNetworkBack() }
        }
    }

    /** RetroAchievements' messages over the game, each said once ([AchievementMessages]). */
    private val achievementMessages by lazy {
        AchievementMessages(
            scope,
            coreDispatcher,
            gameDispatcher,
            object : AchievementsReader {
                override fun queuedOwn() = NativeLibrary.getAchievementsQueue().own

                override fun gameAchievements() = NativeGameCoreApi.getGameAchievements()
            },
            { gameRunning },
        ) { osd.post(it) }
    }

    /** The in-game menu: its rows, its slot panel, and what they do. */
    val gameMenu by lazy {
        InGameMenu(
            scope,
            gameDispatcher,
            NativeGameCoreApi,
            lastSlots,
            gameRecords,
            onMessage = { osd.post(it) },
            onResume = session::closeMenu,
            onExit = session::exit,
            // The running game's own settings, each change applied straight away.
            onSettings = { game ->
                if (game == null) gameMenuSettings.close() else gameMenuSettings.open(game, running = true)
            },
            // A shared slot of another game: it starts once this one has stopped (onGameStopped()).
            onSwitch = session::leavingFor,
        )
    }

    /** What the app keeps per game on this device: its last slot, last disc, and so on. */
    private val gamePrefs by lazy { SharedPreferencesStore(context, "game") }

    /** The on-screen touch controls' settings, on this device, and Port 1's kind for them in the running game. */
    val touchControls by lazy {
        TouchControls(
            TouchPrefs(SharedPreferencesStore(context, "touch")),
            { controllers?.connected?.value?.size ?: 0 },
        ) {
            osd.post(it)
        }
    }
    private val gameRecords by lazy { GameRecords(gamePrefs) }
    private val lastSlots by lazy { LastSlots(gamePrefs) }

    // The RetroAchievements login token, sealed with a Keystore key: never in secrets.ini or the data folder.
    private val achievementsToken by lazy {
        AchievementsToken(KeystoreCipher(), SharedPreferencesStore(context, "achievements"))
    }

    // Memory card sync's secret access key and passphrase, sealed with a Keystore key of their own: never in
    // settings.ini or the data folder.
    private val syncSecrets by lazy {
        SyncSecrets(KeystoreCipher("memcard-sync-secrets"), SharedPreferencesStore(context, "memcard_sync"))
    }

    /** A game's start, which asks first whether to resume where it was left. */
    val gameStarts by lazy {
        GameStarts(
            scope,
            gameDispatcher,
            NativeGameCoreApi,
            gameRecords,
            lastSlots,
            startFrom = session::startFromState,
            starting = { session.starting },
            start = session::start,
        )
    }

    /** The game from the last start, until the core says it stopped. */
    val runningGame get() = session.runningGame

    /** Fast forward is on in the running game: its marker shows over the game. */
    val fastForward get() = gameMenu.state.fastForward

    /** The core has a game running, from onGameStarted to onGameStopped. */
    val gameRunning get() = session.gameRunning

    /** The app is in front, between the activity's resume and its pause. */
    val resumed get() = session.resumed

    /** The controllers, once the core has started. Set on the main thread; the rumble's thread reads it too. */
    @Volatile var controllers: ControllerInput? = null
        private set

    private val workingCopy get() = File(context.filesDir, "data")
    private val manifestFile get() = File(context.filesDir, "mirror-manifest.txt")

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
        // JNI_OnLoad at every launch, setup included (NativeLibrary.load).
        scope.launch(Dispatchers.IO) { NativeLibrary.load() }
        updates =
            makeUpdates(appContext).also { made ->
                // A game left is still shutting down (its resume state, its cards) until the core says it stopped: no
                // check at start meanwhile, and the installer waits for the library too.
                scope.launch { snapshotFlow { screen == Screen.Game || gameActive }.collect { made.onScreen(it) } }
                scope.launch { snapshotFlow { installerWaits(screen, gameActive) }.collect { made.onLibrary(it) } }
            }
        scope.launch { coldStart(folderSettings.load()) }
    }

    private fun makeUpdates(appContext: Context) =
        Updates(
            scope = scope,
            io = Dispatchers.IO,
            source = UpdateSource(),
            prefs = UpdatePrefs(SharedPreferencesStore(appContext, UpdatePrefs.NAME)),
            apkFolder = File(appContext.cacheDir, "updates"),
            own = { ApkCheck.own(appContext) },
            archive = { ApkCheck.ofArchive(appContext, it) },
            // The installer's question and Android's settings, over the app: from the activity when there is one.
            installer =
                Installer(appContext) { intent ->
                    activity?.startActivity(intent)
                        ?: appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            build =
                UpdateBuild(
                    canUpdate = !BuildConfig.DEBUG && BuildConfig.UPDATE_CHANNEL.isNotEmpty(),
                    commit = BuildConfig.GIT_COMMIT,
                    code = BuildConfig.GIT_VERSION_CODE,
                    versionName = BuildConfig.VERSION_NAME,
                ),
        )

    /** The check at start, with the Interface page's settings (desktop's keys), once the core has read them. */
    private fun checkForUpdatesAtStart() {
        val made = updates ?: return
        scope.launch {
            val (enabled, tag) =
                withContext(coreDispatcher) {
                    InterfaceSettings.CHECK_UPDATES_AT_START.read(NativeCoreSettings) to
                        InterfaceSettings.UPDATE_CHANNEL.read(NativeCoreSettings)
                }
            made.startupCheck(tag, enabled)
        }
    }

    /**
     * The activity, and its picker, registered in its onCreate; null when they're gone. It replaces any other: the
     * lifecycle calls below, from an activity that isn't this one, are ignored ([CurrentActivity]).
     */
    fun attach(
        activity: Activity,
        folderPicker: FolderPicker,
        filePicker: DocumentPicker,
    ) {
        currentActivity.attach(activity)
        // A pick under way in the activity this one replaces gets no result: it ends with nothing.
        picker?.takeIf { it !== folderPicker }?.abandon()
        picker = folderPicker
        documentPicker?.takeIf { it !== filePicker }?.abandon()
        documentPicker = filePicker
    }

    fun detach(activity: Activity) {
        if (!currentActivity.isCurrent(activity)) return
        currentActivity.detach(activity)
        picker?.abandon()
        picker = null
        documentPicker?.abandon()
        documentPicker = null
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
                    dataSync.push(force = false)
                }
            }
        // Memory card sync resumes once the pull has brought what the data folder has, if the app is still in front.
        syncVisibility.started()
        if (coreStarted) {
            scope.launch {
                try {
                    resume()
                } finally {
                    syncVisibility.pullDone()
                }
            }
        }
        networkWatch.start()
        // Back in front: an update waiting to be allowed carries on, and the installer's question comes back.
        updates?.onShown(true)
    }

    /**
     * [activity] is in front: a game on screen carries on, unless its menu is open ([PausePolicy]). Ignored unless
     * it's the current activity.
     */
    fun onResume(activity: Activity) {
        if (!isCurrent(activity)) return
        session.onResume()
    }

    /**
     * [activity] is no longer in front: a game on screen lets go of what's held and pauses, which writes its memory
     * cards. Ignored unless it's the current activity: an old one pausing after its replacement resumed would leave
     * the game on screen thought paused.
     */
    fun onPause(activity: Activity) {
        if (!isCurrent(activity)) return
        session.onPause()
    }

    /**
     * [activity] is hidden: everything changed is pushed now, settled or not. Ignored unless it's the current
     * activity: an old one stopping after its replacement started would end the new one's pushes.
     */
    fun onStop(activity: Activity) {
        if (!isCurrent(activity)) return
        // A cover download is for the app in front, and stops with what it found; an edit to a source is saved.
        covers.stop()
        pushTicker?.cancel()
        pushTicker = null
        scope.launch { dataSync.push(force = true) }
        // After the pause's card writes, which the core has queued already: sync pushes them, then rests.
        syncVisibility.stopped()
        networkWatch.stop()
        // Android won't show the installer's question from the background: the update waits for the app.
        updates?.onShown(false)
    }

    // --- Setup ---

    /**
     * Copies the BIOS images in [treeUri], and remembers what it found, if anything. A region whose image isn't among
     * the new copies goes back to Automatic, once the core has started ([dropStaleBiosOverrides]).
     */
    suspend fun importBios(treeUri: String): BiosImportResult = folderSettings.importBios(treeUri)

    /** Whether [treeUri] can be the data folder alongside the [games] folders, and what it holds if it can. */
    suspend fun inspectDataFolder(
        treeUri: String,
        games: List<String>,
    ): DataFolderInspection = folderSettings.inspectDataFolder(treeUri, games)

    /** Lets go of a folder setup picked and then dropped, unless a saved folder still uses it. */
    fun releaseFolder(uri: String) = folderSettings.releaseFolder(uri)

    /** Saves the folders setup chose, then starts as a set-up app does. */
    fun finishSetup(chosen: SavedFolders) {
        // Once: a second Finish, pressed before the library shows, would start everything twice.
        if (!navigator.finishSetup()) return
        folderSettings.use(chosen)
        status.show(Status.Starting, STARTING)
        scope.launch {
            folderSettings.save()
            coldStart(chosen)
        }
    }

    // --- Missing grants ---

    /** The banners for [missingGrants], and for a data folder that can't be reached in [state]. */
    fun banners(state: MirrorState): List<BannerModel> =
        LibraryBanners.build(missingGrants, state, folders.data != null, ::folderLabel, ::repick, ::removeGamesFolder)

    /** Picks [grant]'s folder again ([FolderSettings.repick]). */
    fun repick(grant: MissingGrant) = folderSettings.repick(grant)

    /** Stops listing a games folder whose grant is gone, and releases it. */
    fun removeGamesFolder(uri: String) = folderSettings.removeGamesFolder(uri)

    // --- The library ---

    /** Switches between the grid and the list, and saves the choice in settings.ini. */
    fun toggleView() {
        val grid = !gridView
        gridView = grid
        if (coreStarted) scope.launch(settingsQueue) { NativeLibrary.setBoolSetting(UI, GRID_VIEW, grid) }
    }

    /**
     * Sorts the library by [sort], and saves it in settings.ini as Big Picture keeps it, once the core runs; nothing
     * when it's the sort already, so the keys are only written once the player changes it.
     */
    fun sortLibrary(sort: LibrarySort) = librarySorts.pick(sort)

    /** Scans the games folders again, unless a scan from here is under way. */
    fun refresh() {
        if (!coreStarted || refreshJob?.isActive == true) return
        showMessage("Scanning your games…")
        refreshJob = scope.launch { refreshLibrary(showProgress = true) }
    }

    // --- The settings ([Navigator]) ---

    /** Opens the settings, from the library, on their categories. */
    fun openSettings() = navigator.openSettings()

    /** Back to the library, which focuses its Settings button. */
    fun closeSettings() = navigator.closeSettings()

    /** The settings pages that need the core can open: it has started, and no game is under way. */
    val settingsReady get() = navigator.settingsReady

    /** Opens [entry]'s page from the categories' list, with the focus in it, if it can. */
    fun openSettingsPage(entry: SettingsEntry) = navigator.openSettingsPage(entry)

    /** Shows [entry]'s page beside the categories, in the settings' two panels, with the focus on its category. */
    fun showSettingsPage(entry: SettingsEntry) = navigator.showSettingsPage(entry)

    /** The focus goes into the open page, in the settings' two panels. */
    fun enterSettingsPage() = navigator.enterSettingsPage()

    /** Back from a settings page to the categories' list, which focuses the page's entry, if the page can close. */
    fun closeSettingsPage() = navigator.closeSettingsPage()

    /** Back to the library from the settings, with a page open or not, if the page can close. */
    fun leaveSettings() = navigator.leaveSettings()

    // --- A game's own settings ---

    /** The game the library focuses next, over the last one launched ([Navigator.libraryFocus]). */
    val libraryFocus get() = navigator.libraryFocus

    /** A game's Saves page, from its library tile. */
    val savesPage by lazy {
        SavesPage(scope, gameDispatcher, SaveReader(NativeGameCoreApi, gameDispatcher), NativeGameCoreApi, { games }) {
            game,
            state,
            refused,
            ->
            gameStarts.playFromState(game, state, refused)
        }
    }

    /** Opens [game]'s Saves, from the library. */
    fun openSaves(game: GameEntry) = navigator.openSaves(game)

    /** Back to the library, which focuses the game. */
    fun closeSaves() = navigator.closeSaves()

    /** Opens [game]'s own settings, from the library, if the settings can open and it has a key to keep them under. */
    fun openGameSettings(game: GameEntry) = navigator.openGameSettings(game)

    /** Back to the library, which focuses the game. */
    fun closeGameSettings() = navigator.closeGameSettings()

    // --- The memory card editor ---

    /** Opens the memory card editor, from the Memory cards page, if the settings can open: no game is under way. */
    fun openMemcardEditor() = navigator.openMemcardEditor()

    /** Back from the editor to the Memory cards page, which counts the cards again. */
    fun closeMemcardEditor() = navigator.closeMemcardEditor()

    // Cards the data folder or sync changed while the editor shows: read again, unless they have edits.
    private fun refreshEditor() {
        if (screen == Screen.MemcardEditor) memcardEditor.refresh()
    }

    // --- Memory card sync's conflicts ---

    /** Opens the conflict screen, from the Memory cards page or the library's badge, if the settings can open. */
    fun openSyncConflicts() = navigator.openSyncConflicts()

    /** The library's sync badge: the conflict screen when there are conflicts, else Settings > Memory cards. */
    fun openSyncBadge() = navigator.openSyncBadge()

    /** Back from the conflict screen to where it was opened from. */
    fun closeSyncConflicts() = navigator.closeSyncConflicts()

    // --- The Controllers page ([ControllersPage]) ---

    /** Back on the Controllers page: one view up, and out of the page ([leave]) from its hub. */
    fun controllersBack(leave: () -> Unit) {
        if (!controllersPage.back()) leave()
    }

    // --- The folder settings ---

    /** Picks another BIOS folder, which replaces the old one if it holds any images. */
    fun changeBios() = folderSettings.changeBios()

    /** Imports the BIOS folder's images again. */
    fun rescanBios() = folderSettings.rescanBios()

    fun addGamesFolder() = folderSettings.addGamesFolder()

    /** Saves [games] as the games folders, releases the removed ones, and refreshes the library. */
    fun setGamesFolders(games: List<String>) = folderSettings.setGamesFolders(games)

    /** Picks a new data folder, then waits for the user's choice of what to do with it ([changeDataFolder]). */
    fun pickDataFolder() = folderSettings.pickDataFolder()

    /** Drops the data folder picked, and its grant unless a saved folder holds it. */
    fun cancelDataChange() = folderSettings.cancelDataChange()

    /** Makes the picked data folder the data folder, as [choice] says, then restarts the process. */
    fun changeDataFolder(choice: DataFolderChoice) = folderSettings.changeDataFolder(choice)

    /** The data folder's state, for the folder settings. */
    fun dataStatus(state: MirrorState): String = LibraryBanners.dataStatus(state, folderSettings.dataGranted)

    /** The data folder holds the latest changes, as far as the app knows. */
    fun dataReachable(state: MirrorState): Boolean = folderSettings.dataReachable(state)

    // --- Games ---

    /** Starts [game], once it's asked whether to resume where it was left, if it has a resume state ([gameStarts]). */
    fun startGame(game: GameEntry) {
        if (!ready || gameActive || session.starting) return
        gameStarts.play(game)
    }

    // The game the in-game menu left for, from its shared slot, now that the last one has stopped.
    private fun startNext(next: StateStart) {
        val entry = games.firstOrNull { it.path == next.gamePath }
        if (entry == null) return showError(GONE_FROM_LIBRARY)
        gameStarts.playFromState(entry, next.state, ::showError)
    }

    /** Back to the library, which waits for the game's stop before it starts another ([GameSession.leave]). */
    fun leaveGame() = session.leave()

    /** Pauses the game and shows its menu, from Back, Mode or Select + Start held ([GameSession.openMenu]). */
    fun openMenu() = session.openMenu()

    /** Pauses the game, which writes its memory cards ([GameSession.pause]). */
    fun pauseGame() = session.pause()

    /** Closes the menu, and carries on with the game if the app is in front ([GameSession.closeMenu]). */
    fun closeMenu() = session.closeMenu()

    /** Closes the menu, and stops the game: back to the library ([GameSession.exitToLibrary]). */
    fun exitToLibrary() = session.exitToLibrary()

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
            // onGameStopped() follows.
            session.stopForEject()
        }
        if (EjectAction.StopMirror in actions) dataSync.mirror?.hold(CARD_REMOVED)
    }

    /**
     * The volume [volumeId] is back: a data folder on it is mirrored again, starting with a push now, so the changes
     * held back go out and the banner goes without waiting for the next tick.
     */
    private suspend fun onVolumeMounted(volumeId: String) {
        if (!ejectedVolumes.remove(volumeId)) return
        val data = folders.data ?: return
        if (!VolumeMatch.treeOnVolume(data, volumeId)) return
        val mirror = dataSync.mirror ?: return
        mirror.letGo()
        dataSync.push(force = false)
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
        folderSettings.use(saved)
        if (saved.setupComplete) folderSettings.checkGrants()
        val steps = StartupPlan.steps(saved, missingGrants, coreStarted = false)
        if (StartupStep.ShowSetup in steps) {
            status.clear()
            navigator.showSetup()
            // No settings.ini yet: the defaults, on the build's own channel.
            updates?.startupCheck(InterfaceSettings.UPDATE_CHANNEL.default, enabled = true)
            return
        }
        navigator.showLibrary()
        // A missing grant still has a mirror, for when the folder is picked again.
        saved.data?.let(dataSync::useMirror)
        for (step in steps) {
            when (step) {
                StartupStep.Pull -> dataSync.firstPull()
                StartupStep.InitializeCore -> {
                    if (!initializeCore()) return
                    // The folders the core's settings.ini names beyond the defaults, which the first pull left out,
                    // then the cards the game settings in them name: no game starts before they're in.
                    status.progress(Status.Copying, COPYING)
                    dataSync.catchUp()?.join()
                    ready = true
                }
                StartupStep.SetGameFolders -> applyGameFolders()
                StartupStep.RefreshLibrary -> refreshLibrary(showProgress = true)
                StartupStep.ShowSetup, StartupStep.ReloadSettings, StartupStep.ReadCorePaths -> {}
            }
        }
        checkForUpdatesAtStart()
    }

    /** Back in front: grants again, then a pull, settings.ini reloaded if it changed, and the library refreshed. */
    private suspend fun resume() {
        folderSettings.checkGrants()
        // A pull that reloaded the settings has rescanned the library already.
        var refreshed = false
        for (step in StartupPlan.steps(folders, missingGrants, coreStarted = true)) {
            when (step) {
                StartupStep.Pull -> refreshed = dataSync.pull(onlyMemcards = false)
                StartupStep.RefreshLibrary -> if (screen == Screen.Library && !refreshed) refreshLibrary()
                else -> {}
            }
        }
    }

    // Whether memory card sync rests in the background, as the app is shown and hidden.
    private val syncVisibility = SyncVisibility { NativeLibrary.setMemcardSyncBackgrounded(it) }

    // initialize() waits for the core's thread to start, and the resources are copied out first.
    private suspend fun initializeCore(): Boolean {
        status.progress(Status.StartingCore, "Starting the core…")
        val result =
            withContext(coreDispatcher) {
                runCatching {
                    val biosDir = File(context.filesDir, "bios").apply { mkdirs() }
                    workingCopy.mkdirs()
                    val resources = Resources.extract(context)
                    NativeLibrary.setCallbacks(coreEvents)
                    // The app's own storage, never the data folder: packs aren't mirrored.
                    NativeLibrary.setShaderPacksFolder(File(context.filesDir, "shaderpacks").absolutePath)
                    // Texture packs are read from the data folder in place: the mirror doesn't copy them.
                    NativeLibrary.setDataFolder(TexturePacks.folderFor(folders.data))
                    NativeLibrary
                        .initialize(
                            workingCopy.absolutePath,
                            resources.absolutePath,
                            biosDir.absolutePath,
                            prefs.installId(),
                            achievementsToken.load(),
                            File(context.filesDir, "achievements").absolutePath,
                            SyncSettings.defaultDeviceName(),
                        ).also {
                            if (it) {
                                readLibraryView()
                                dataSync.readCorePaths()
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
        syncVisibility.coreStarted()
        librarySorts.coreStarted()
        memcardSyncStatus.refresh()
        if (biosOverridesToCheck) dropStaleBiosOverrides()
        // Reported once the core takes input; it keeps connections made before then anyway.
        controllers =
            ControllerInput(context).also {
                it.onMenuRequested = session::openMenu
                it.onControllerDisconnected = session::controllerDisconnected
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
        withContext(settingsQueue) {
            val copies = File(context.filesDir, "bios").list()?.toList().orEmpty()
            val stale = BiosOverrides.stale(BiosOverrides.load(NativeCoreSettings), copies)
            if (stale.isEmpty()) return@withContext
            Log.i(TAG, "Back to Automatic, as the BIOS folder no longer has their image: $stale")
            stale.forEach { BiosOverrides.set(NativeCoreSettings, it, null) }
            NativeLibrary.applySettings()
        }
    }

    // On coreDispatcher, once the core has read settings.ini: the view and the sort.
    private suspend fun readLibraryView() {
        val grid = NativeLibrary.getBoolSetting(UI, GRID_VIEW, true)
        val sort = LibraryPrefs.read(NativeCoreSettings)
        withContext(Dispatchers.Main) {
            gridView = grid
            librarySorts.read(sort)
        }
    }

    private suspend fun applyGameFolders() {
        val readable = folderSettings.readableGameFolders()
        withContext(settingsQueue) { NativeLibrary.setGameFolders(readable) }
    }

    // After a games folder change. Before the core runs, its start passes the folders itself.
    private suspend fun updateLibrary() {
        if (!coreStarted) return
        applyGameFolders()
        refreshLibrary()
    }

    // The library's tiles as the core lists them now, without a scan: their covers looked up again.
    private suspend fun rereadLibrary() {
        if (!coreStarted) return
        games = withContext(coreDispatcher) { NativeLibrary.getGameList().toList() }
    }

    private suspend fun refreshLibrary(showProgress: Boolean = false) {
        if (!coreStarted) return
        if (showProgress) status.progress(Status.Scanning, "Scanning your games…")
        games =
            withContext(coreDispatcher) {
                NativeLibrary.refreshGameList(false)
                NativeLibrary.getGameList().toList()
            }
        // Its own line only: one shown meanwhile (a refused start, a folder's answer) stays.
        if (showProgress) status.clear(Status.Scanning)
    }

    private fun showMessage(text: String) = status.show(Status.Note, text)

    private fun showError(text: String) = status.error(text)

    // --- The core's events ([CoreEvents]) ---

    // What the core's events reach on the main thread, once posted there.
    private val mainEvents =
        object : MainEvents {
            override fun gameStarted(
                title: String,
                serial: String,
                startPaused: Boolean,
                pauseOnDisconnect: Boolean,
            ) {
                Log.i(TAG, "Game started: $title")
                session.started(title, serial, startPaused, pauseOnDisconnect)
            }

            override fun gameStopped() {
                Log.i(TAG, "Game stopped.")
                session.stopped()
            }

            override fun error(message: String) {
                Log.w(TAG, "Error: $message")
                showError(message)
            }

            override fun showMessage(
                text: String,
                key: String?,
                seconds: Float?,
                icon: ArgbImage?,
            ) {
                if (seconds == null) osd.post(text, key, icon = icon) else osd.post(text, key, seconds, icon)
            }

            override fun removeMessage(key: String) = osd.remove(key)

            override fun memcardSyncChanged() {
                if (coreStarted) memcardSyncStatus.refresh()
                refreshEditor()
            }

            override fun achievementsLoginExpired() {
                achievementMessages.loginExpired()
                achievementsPage.loginExpired()
            }

            override fun achievementsChanged() {
                gameMenu.achievementsChanged()
                if (screen == Screen.Achievements) achievementsPage.refresh()
                achievementMessages.achievementsChanged()
            }

            override fun achievementCard(card: AchievementNotice.Card) = achievementOverlay.post(card)

            override fun achievementIndicator(
                kind: Int,
                id: Int,
                shown: Boolean,
                text: String,
                badgePath: String,
            ) = achievementOverlay.indicator(kind, id, shown, text, badgePath)

            override fun leaderboard(
                request: Int,
                page: LeaderboardPage?,
                error: String?,
            ) = gameMenu.leaderboardFetched(request, page, error)

            override fun achievementImage(path: String) = badges.downloaded(path)

            override fun fastForwardChanged(enabled: Boolean) = gameMenu.fastForwardChanged(enabled)

            override fun padKindChanged(kind: PadKind?) {
                touchControls.kind = kind
            }

            override fun hotkey(hotkey: StateHotkey) = gameMenu.hotkey(hotkey)
        }

    // What the core's events reach on its own thread, before it goes on.
    private val coreThreadEvents =
        object : CoreThreadEvents {
            // The token lives only in the core's memory until it's stored here.
            override fun storeAchievementsToken(token: String?) = achievementsToken.store(token)

            override fun storeMemcardSyncSecret(
                name: String,
                value: String?,
            ) = syncSecrets.store(name, value)

            override fun loadMemcardSyncSecret(name: String) = syncSecrets.load(name)

            override fun hasMemcardSyncSecret(name: String) = syncSecrets.has(name)

            override fun bindingCaptured(binding: String) = controllersPage.onBindingCaptured(binding)

            override fun rumble(
                deviceId: Int,
                large: Float,
                small: Float,
            ) = rumble.onRumble(deviceId, large, small)

            override fun playSound(path: String) = achievementSounds.play(path)
        }

    /** The core's callbacks, registered as it initializes. */
    private val coreEvents: CoreEvents =
        CoreEvents({ block -> mainHandler.post(block) }, mainEvents, coreThreadEvents, scope, Dispatchers.IO) {
            ArgbImages.decode(it, ICON_PX)
        }

    /**
     * The controllers with a hotkey bound, whose Select the game sees only once it's known not to be a chord
     * ([io.github.ilyalissoboi.goosestation.input.SelectChord]). Read as the game starts: hotkeys change only outside
     * a game, and a controller connected during one has its Select held back from the next.
     */
    private fun readSelectChords() {
        val input = controllers ?: return
        val connected = input.connected.value.map { it.descriptor }
        scope.launch {
            val chorded =
                withContext(gameDispatcher) {
                    connected.filter { descriptor ->
                        NativeLibrary.getHotkeyBindings(descriptor)?.any { it.binding != null } == true
                    }
                }
            if (gameRunning) input.selectChordDescriptors = chorded.toSet()
        }
    }
}
