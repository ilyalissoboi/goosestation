// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.ilyalissoboi.goosestation.app.AppController
import io.github.ilyalissoboi.goosestation.app.ControllersView
import io.github.ilyalissoboi.goosestation.app.CorePage
import io.github.ilyalissoboi.goosestation.app.MemcardEditorWords
import io.github.ilyalissoboi.goosestation.app.PausePolicy
import io.github.ilyalissoboi.goosestation.app.Screen
import io.github.ilyalissoboi.goosestation.app.SyncBadges
import io.github.ilyalissoboi.goosestation.app.TouchVisibility
import io.github.ilyalissoboi.goosestation.app.Updates
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.storage.DocumentPicker
import io.github.ilyalissoboi.goosestation.storage.FolderPicker
import io.github.ilyalissoboi.goosestation.ui.AchievementsScreen
import io.github.ilyalissoboi.goosestation.ui.AdvancedScreen
import io.github.ilyalissoboi.goosestation.ui.AudioScreen
import io.github.ilyalissoboi.goosestation.ui.BiosScreen
import io.github.ilyalissoboi.goosestation.ui.CardSlots
import io.github.ilyalissoboi.goosestation.ui.ConsoleScreen
import io.github.ilyalissoboi.goosestation.ui.ControllersScreen
import io.github.ilyalissoboi.goosestation.ui.CoversScreen
import io.github.ilyalissoboi.goosestation.ui.EmulationScreen
import io.github.ilyalissoboi.goosestation.ui.FoldersScreen
import io.github.ilyalissoboi.goosestation.ui.GLYPH_TYPES
import io.github.ilyalissoboi.goosestation.ui.GameScreen
import io.github.ilyalissoboi.goosestation.ui.GameSettingsHost
import io.github.ilyalissoboi.goosestation.ui.GraphicsScreen
import io.github.ilyalissoboi.goosestation.ui.HotkeysScreen
import io.github.ilyalissoboi.goosestation.ui.InterfaceScreen
import io.github.ilyalissoboi.goosestation.ui.LibraryScreen
import io.github.ilyalissoboi.goosestation.ui.MacroScreen
import io.github.ilyalissoboi.goosestation.ui.MemcardEditorScreen
import io.github.ilyalissoboi.goosestation.ui.MemcardSyncScreen
import io.github.ilyalissoboi.goosestation.ui.PortScreen
import io.github.ilyalissoboi.goosestation.ui.PostProcessingScreen
import io.github.ilyalissoboi.goosestation.ui.ResumePrompt
import io.github.ilyalissoboi.goosestation.ui.SavesScreen
import io.github.ilyalissoboi.goosestation.ui.SettingsScreen
import io.github.ilyalissoboi.goosestation.ui.SetupScreen
import io.github.ilyalissoboi.goosestation.ui.SyncConflictsScreen
import io.github.ilyalissoboi.goosestation.ui.UpdateBannerModel
import io.github.ilyalissoboi.goosestation.ui.components.SettingsLoadingPage
import io.github.ilyalissoboi.goosestation.ui.deviceTicker
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.ZoneId
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    // Registered here, before the activity starts, as the result API needs.
    private lateinit var folderPicker: FolderPicker
    private lateinit var documentPicker: DocumentPicker

    private val inGame get() = AppController.screen == Screen.Game

    // The controllers' input goes to the core: in a game with its prompt closed, or while a capture takes the next
    // input as a binding.
    private val padInput get() = (inGame && !AppController.promptOpen) || AppController.capturingInput

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderPicker = FolderPicker(this)
        documentPicker = DocumentPicker(this)
        AppController.attach(this, folderPicker, documentPicker)
        // The app is always dark, whatever the system's theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        AppController.start(applicationContext)

        setContent {
            GooseStationTheme {
                when (AppController.screen) {
                    Screen.Setup ->
                        SetupScreen(
                            pickFolder = folderPicker::pick,
                            importBios = AppController::importBios,
                            inspectDataFolder = AppController::inspectDataFolder,
                            releaseFolder = AppController::releaseFolder,
                            onFinish = AppController::finishSetup,
                        )
                    Screen.Game -> {
                        val touch = AppController.touchControls
                        val pads by (AppController.controllers?.connected ?: NO_CONTROLLERS).collectAsState()
                        val ticker = remember { deviceTicker(applicationContext) }
                        GameScreen(
                            menuOpen = AppController.promptOpen,
                            onOpenMenu = AppController::openPrompt,
                            menu = AppController.gameMenu.state,
                            actions = AppController.gameMenu,
                            game = AppController.games.firstOrNull { it.path == AppController.lastGamePath },
                            osd = AppController.osd.shown,
                            fastForward = AppController.fastForward,
                            osdExpiry = AppController.osd.nextExpiry(),
                            onOsdExpire = AppController.osd::expire,
                            badge = AppController.badges::image,
                            card = AppController.achievementOverlay.card,
                            indicators = AppController.achievementOverlay.indicators,
                            overlayExpiry = AppController.achievementOverlay.nextExpiry(),
                            onOverlayExpire = AppController.achievementOverlay::expire,
                            settings = {
                                GameSettingsHost(
                                    AppController.gameMenuSettings,
                                    AppController.shaderPacks,
                                    hardcoreActive = AppController.gameMenu.state.hardcore,
                                    onBack = AppController.gameMenu::closeSettings,
                                )
                            },
                            touch = TouchVisibility.of(touch.settings, pads.size, true, false, touch.kind),
                            touchSettings = touch.settings,
                            touchSink = {
                                NativeLibrary.setTouchPad(
                                    it.buttons,
                                    it.leftX,
                                    it.leftY,
                                    it.rightX,
                                    it.rightY,
                                )
                            },
                            ticker = ticker.takeIf { touch.settings.vibration },
                            onPictureAtTop = NativeLibrary::setPictureAtTop,
                            touchKey = touch.releases,
                        )
                    }
                    Screen.Settings ->
                        SettingsScreen(
                            focusEntry = AppController.settingsEntry,
                            coreReady = AppController.settingsReady,
                            onOpen = AppController::openSettingsPage,
                            onBack = AppController::closeSettings,
                        )
                    Screen.Interface ->
                        CorePageRoute(CorePage.Interface) { values, onChange ->
                            InterfaceScreen(values, onChange, onBack = AppController::closeSettingsPage)
                        }
                    Screen.Console ->
                        CorePageRoute(CorePage.Console) { values, onChange ->
                            ConsoleScreen(
                                values,
                                AppController.settingsPages.names,
                                onChange,
                                onBack = AppController::closeSettingsPage,
                            )
                        }
                    Screen.Emulation ->
                        CorePageRoute(CorePage.Emulation) { values, onChange ->
                            EmulationScreen(
                                values,
                                AppController.settingsPages.rewindUsage,
                                onChange,
                                onBack = AppController::closeSettingsPage,
                            )
                        }
                    Screen.PostProcessing ->
                        PostProcessingScreen(
                            AppController.postProcessingPage,
                            AppController.shaderPacks,
                            onBack = AppController::closeSettingsPage,
                        )
                    Screen.Advanced ->
                        CorePageRoute(CorePage.Advanced) { values, onChange ->
                            AdvancedScreen(
                                values,
                                AppController.settingsPages.names,
                                AppController.settingsPages.fastmemAvailable,
                                onChange,
                                onBack = AppController::closeSettingsPage,
                            )
                        }
                    Screen.Audio ->
                        CorePageRoute(CorePage.Audio) { values, onChange ->
                            AudioScreen(
                                values,
                                AppController.settingsPages.names,
                                onChange,
                                onBack = AppController::closeSettingsPage,
                            )
                        }
                    Screen.Graphics ->
                        CorePageRoute(CorePage.Graphics) { values, onChange ->
                            GraphicsScreen(
                                values = values,
                                names = AppController.settingsPages.names,
                                vulkanFailed = AppController.settingsPages.vulkanFailed,
                                onChange = onChange,
                                onBack = AppController::closeSettingsPage,
                            )
                        }
                    Screen.Achievements -> {
                        val page = AppController.achievementsPage
                        val account = page.account
                        val settings = page.settings
                        if (account == null || settings == null) {
                            SettingsLoadingPage("Achievements", onBack = AppController::closeSettingsPage)
                        } else {
                            AchievementsScreen(
                                account = account,
                                settings = settings,
                                signingIn = page.signingIn,
                                error = page.error,
                                expired = page.expired,
                                passwordCleared = page.passwordCleared,
                                queue = page.queue,
                                onSignIn = page::signIn,
                                onSignOut = page::signOut,
                                onChange = page::change,
                                onBack = AppController::closeSettingsPage,
                            )
                        }
                    }
                    Screen.MemoryCards -> {
                        val page = AppController.memcardSyncPage
                        val state = page.state
                        if (state == null) {
                            SettingsLoadingPage("Memory cards", onBack = AppController::closeSettingsPage)
                        } else {
                            MemcardSyncScreen(
                                state = state,
                                onEnabled = page::setEnabled,
                                onText = page::setText,
                                onSecret = page::storeSecret,
                                onRemovePassphrase = page::removePassphrase,
                                onSyncNow = page::syncNow,
                                onOpenConflicts = AppController::openSyncConflicts,
                                cardsSummary = MemcardEditorWords.cardCount(AppController.memcardEditor.cardCount),
                                onManageCards = AppController::openMemcardEditor,
                                onBack = AppController::closeSettingsPage,
                                cards =
                                    AppController.settingsPages.values(CorePage.MemoryCards)?.let {
                                        CardSlots(
                                            it,
                                            AppController.settingsPages.names,
                                            AppController.settingsPages.sharedCards,
                                            onChange = { new ->
                                                AppController.settingsPages.change(CorePage.MemoryCards, new)
                                            },
                                        )
                                    },
                            )
                        }
                    }
                    Screen.GameSettings ->
                        GameSettingsHost(
                            AppController.gameSettingsPages,
                            AppController.shaderPacks,
                            hardcoreActive = false,
                            onBack = AppController::closeGameSettings,
                        )
                    Screen.Saves -> {
                        val page = AppController.savesPage
                        page.state?.let {
                            SavesScreen(it, page, ZonedDateTime.now(), onBack = AppController::closeSaves)
                        }
                    }
                    Screen.MemcardEditor -> {
                        val editor = AppController.memcardEditor
                        val ui = editor.ui
                        if (ui == null) {
                            SettingsLoadingPage("Memory cards", onBack = AppController::closeMemcardEditor)
                        } else {
                            MemcardEditorScreen(ui = ui, actions = editor, onLeave = AppController::closeMemcardEditor)
                        }
                    }
                    Screen.SyncConflicts -> {
                        val page = AppController.memcardSyncPage
                        val conflicts = page.conflicts
                        if (conflicts == null) {
                            SettingsLoadingPage("Sync conflicts", onBack = AppController::closeSyncConflicts)
                        } else {
                            SyncConflictsScreen(
                                conflicts = conflicts,
                                zone = ZoneId.systemDefault(),
                                onResolve = page::resolve,
                                onKeepAll = page::keepAllLocal,
                                onBack = AppController::closeSyncConflicts,
                            )
                        }
                    }
                    Screen.Bios -> {
                        val bios = AppController.settingsPages.bios
                        if (bios == null) {
                            SettingsLoadingPage("BIOS", onBack = AppController::closeSettingsPage)
                        } else {
                            BiosScreen(
                                images = bios.images,
                                auto = bios.auto,
                                overrides = bios.overrides,
                                fastBoot = bios.fastBoot,
                                onSetOverride = AppController.settingsPages::setBiosOverride,
                                onFastBoot = AppController.settingsPages::setFastBoot,
                                onBack = AppController::closeSettingsPage,
                            )
                        }
                    }
                    Screen.Controllers -> {
                        val page = AppController.controllersPage
                        val state = page.state
                        when (val view = state.view) {
                            ControllersView.Hub ->
                                ControllersScreen(
                                    controllers = state.controllers,
                                    labels = state.labels,
                                    ports = state.ports,
                                    rumble = state.rumble,
                                    offered = state.offered,
                                    portTypes = state.portTypes,
                                    typeNames = state.typeNames,
                                    multitap = state.multitap,
                                    multitapNames = state.multitapNames,
                                    onPort = page::setPort,
                                    onRumble = page::setRumble,
                                    onMultitap = page::setMultitap,
                                    onOpenPort = page::openPort,
                                    onOpenHotkeys = page::openHotkeys,
                                    onBack = AppController::controllersBack,
                                    cameFrom = state.cameFrom,
                                    touch = AppController.touchControls.settings,
                                    onTouch = { AppController.touchControls.settings = it },
                                )
                            is ControllersView.Port -> {
                                val bindings = state.bindings
                                val title =
                                    state.offered.firstOrNull { it.value == view.pad }?.label ?: "Port ${view.pad + 1}"
                                if (bindings == null) {
                                    SettingsLoadingPage(title, onBack = AppController::controllersBack)
                                } else {
                                    PortScreen(
                                        title = title,
                                        controllers =
                                            state.controllers.filter {
                                                (state.ports[it.descriptor] ?: 0) ==
                                                    view.pad
                                            },
                                        labels = state.labels,
                                        selected = state.selected,
                                        bindings = bindings,
                                        bindingsFor = state.bindingsFor,
                                        capturing = state.capturing,
                                        secondsLeft = state.secondsLeft,
                                        undoFor = state.undo?.name,
                                        onSelect = AppController::selectController,
                                        onCapture = AppController::captureBinding,
                                        glyphs = state.bindingsType in GLYPH_TYPES,
                                        onCancelCapture = AppController::cancelCapture,
                                        onClear = AppController::clearBinding,
                                        onUndo = page::undo,
                                        onDropUndo = page::dropUndo,
                                        onReset = AppController::resetBindings,
                                        onBack = AppController::controllersBack,
                                        pad = view.pad,
                                        type = state.portTypes[view.pad],
                                        typeNames = state.typeNames,
                                        onType = { page.setType(view.pad, it) },
                                        typeSettings = state.typeSettings,
                                        values = state.portValues,
                                        onChange = page::changePort,
                                        onOpenMacro = { page.openMacro(view.pad, it) },
                                        focusMacro = (state.cameFrom as? ControllersView.Macro)?.macro,
                                    )
                                }
                            }
                            is ControllersView.Macro -> {
                                val port =
                                    state.offered.firstOrNull { it.value == view.pad }?.label ?: "Port ${view.pad + 1}"
                                MacroScreen(
                                    title = "Macro ${view.macro} · $port",
                                    pad = view.pad,
                                    macro = view.macro,
                                    controllers =
                                        state.controllers.filter {
                                            (state.ports[it.descriptor] ?: 0) ==
                                                view.pad
                                        },
                                    labels = state.labels,
                                    selected = state.selected,
                                    triggers = state.macros.orEmpty(),
                                    triggersFor = state.bindingsFor.takeIf { state.macros != null },
                                    inputs = state.macroInputs,
                                    values = state.macroValues,
                                    capturing = state.capturing,
                                    secondsLeft = state.secondsLeft,
                                    undoFor = state.undo?.name,
                                    onSelect = AppController::selectController,
                                    onCapture = AppController::captureBinding,
                                    onCancelCapture = AppController::cancelCapture,
                                    onClear = AppController::clearBinding,
                                    onUndo = page::undo,
                                    onDropUndo = page::dropUndo,
                                    onChange = page::changeMacro,
                                    onBack = AppController::controllersBack,
                                    port = port,
                                )
                            }
                            ControllersView.Hotkeys ->
                                HotkeysScreen(
                                    controllers = state.controllers,
                                    labels = state.labels,
                                    selected = state.selected,
                                    hotkeys = state.hotkeys.orEmpty(),
                                    hotkeysFor = state.bindingsFor.takeIf { state.hotkeys != null },
                                    capturing = state.capturing,
                                    secondsLeft = state.secondsLeft,
                                    undoFor = state.undo?.name,
                                    onSelect = AppController::selectController,
                                    onCaptureHotkey = AppController::captureHotkey,
                                    onCancelCapture = AppController::cancelCapture,
                                    onClear = AppController::clearBinding,
                                    onUndo = page::undo,
                                    onDropUndo = page::dropUndo,
                                    onBack = AppController::controllersBack,
                                )
                        }
                    }
                    Screen.Covers -> CoversScreen(AppController.covers, onBack = AppController::closeSettingsPage)
                    Screen.Folders -> {
                        val mirrorState by AppController.mirrorState.collectAsState()
                        FoldersScreen(
                            folders = AppController.folders,
                            biosImages = AppController.biosImages,
                            dataStatus = AppController.dataStatus(mirrorState),
                            dataReachable = AppController.dataReachable(mirrorState),
                            candidate = AppController.dataCandidate,
                            progress = AppController.dataChange,
                            message = AppController.message,
                            isError = AppController.messageIsError,
                            onChangeBios = AppController::changeBios,
                            onRescanBios = AppController::rescanBios,
                            onAddGames = AppController::addGamesFolder,
                            onSetGames = AppController::setGamesFolders,
                            onChangeData = AppController::pickDataFolder,
                            onChooseData = AppController::changeDataFolder,
                            onCancelData = AppController::cancelDataChange,
                            onBack = AppController::closeFolders,
                        )
                    }
                    Screen.Library -> {
                        val mirrorState by AppController.mirrorState.collectAsState()
                        LibraryScreen(
                            games = AppController.games,
                            gridView = AppController.gridView,
                            banners = AppController.banners(mirrorState),
                            message = AppController.message,
                            isError = AppController.messageIsError,
                            onToggleView = AppController::toggleView,
                            onOpenSettings = AppController::openSettings,
                            onRefresh = AppController::refresh,
                            onStartGame = AppController::startGame,
                            onGameSettings = AppController::openGameSettings,
                            onSaves = AppController::openSaves,
                            focusPath = AppController.libraryFocus ?: AppController.lastGamePath,
                            focusSettings = AppController.focusSettings,
                            syncBadge = SyncBadges.of(AppController.memcardSyncStatus.state),
                            onSyncBadge = AppController::openSyncBadge,
                            sort = AppController.librarySort,
                            onSort = AppController::sortLibrary,
                            search = AppController.librarySearch,
                            overlayOpen = AppController.gameStarts.offer != null,
                            update = AppController.updates?.takeIf { it.bannerShown }?.let(::updateBanner),
                        )
                        val starts = AppController.gameStarts
                        starts.offer?.let { offer ->
                            ResumePrompt(
                                offer,
                                onResume = starts::resume,
                                onStartOver = starts::startOver,
                                onCancel = starts::cancel,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppController.onStart(this)
    }

    override fun onResume() {
        super.onResume()
        AppController.resumed = true
        // A game left paused under its prompt stays so.
        val running = PausePolicy.running(AppController.resumed, AppController.promptOpen)
        if (inGame && AppController.gameRunning && running) NativeLibrary.setPaused(false)
    }

    override fun onPause() {
        AppController.resumed = false
        if (inGame) {
            AppController.controllers?.releaseAll()
            AppController.touchControls.release()
            AppController.pauseGame()
        }
        super.onPause()
    }

    override fun onStop() {
        AppController.onStop(this)
        super.onStop()
    }

    // Losing focus (the notification shade, a dialog) sends a cancelled key-up for held keys, but nothing for axes.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && inGame) {
            AppController.controllers?.releaseAll()
            AppController.touchControls.release()
        }
    }

    override fun onDestroy() {
        // Not an old activity destroyed after its replacement started: the game would be the new one's.
        if (isFinishing && inGame && AppController.isCurrent(this)) AppController.leaveGame()
        AppController.detach(this)
        super.onDestroy()
    }

    // With padInput, controllers go to the core; otherwise they move Compose's focus. A key-up goes where its key-down
    // went, either way, and a stick the core was given stays its until it's back near rest. Lint's RestrictedApi is a
    // false positive here: the restricted one is androidx.core's override of Activity.dispatchKeyEvent, which is public.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val controllers = AppController.controllers
        if (controllers != null && controllers.onKeyEvent(event, padInput)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val controllers = AppController.controllers
        if (controllers != null && controllers.onMotionEvent(event, padInput)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}

/** [page] once its values are read ([content], given them and where its changes go), its loading page until then. */
@Composable
private fun CorePageRoute(
    page: CorePage,
    content: @Composable (SettingValues, (SettingValues) -> Unit) -> Unit,
) {
    val values = AppController.settingsPages.values(page)
    if (values == null) {
        SettingsLoadingPage(page.title, onBack = AppController::closeSettingsPage)
    } else {
        content(values) { AppController.settingsPages.change(page, it) }
    }
}

// The connected controllers before the core has started: none.
private val NO_CONTROLLERS = MutableStateFlow(emptyList<ConnectedController>())

/** The library's update banner, over [updates]. */
private fun updateBanner(updates: Updates) =
    UpdateBannerModel(
        state = updates.state,
        ownVersion = updates.build.versionName,
        failureMessage = updates.failureMessage,
        onUpdate = updates::update,
        onSkip = updates::skip,
        onHide = updates::hide,
        onCancel = updates::cancel,
        onOpenSettings = updates::openPermissionSettings,
        loadChanges = updates::changes,
    )
