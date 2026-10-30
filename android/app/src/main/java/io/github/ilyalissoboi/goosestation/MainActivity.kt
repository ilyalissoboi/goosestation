// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.ilyalissoboi.goosestation.app.AppController
import io.github.ilyalissoboi.goosestation.app.PausePolicy
import io.github.ilyalissoboi.goosestation.app.Screen
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import io.github.ilyalissoboi.goosestation.storage.FolderPicker
import io.github.ilyalissoboi.goosestation.ui.BiosScreen
import io.github.ilyalissoboi.goosestation.ui.ControllersScreen
import io.github.ilyalissoboi.goosestation.ui.FoldersScreen
import io.github.ilyalissoboi.goosestation.ui.GameScreen
import io.github.ilyalissoboi.goosestation.ui.GraphicsScreen
import io.github.ilyalissoboi.goosestation.ui.LibraryScreen
import io.github.ilyalissoboi.goosestation.ui.SettingsScreen
import io.github.ilyalissoboi.goosestation.ui.SetupScreen
import io.github.ilyalissoboi.goosestation.ui.components.SettingsLoadingPage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

class MainActivity : ComponentActivity() {
    // Registered here, before the activity starts, as the result API needs.
    private lateinit var folderPicker: FolderPicker

    private val inGame get() = AppController.screen == Screen.Game

    // The controllers' input goes to the core: in a game with its prompt closed, or while a capture takes the next
    // input as a binding.
    private val padInput get() = (inGame && !AppController.promptOpen) || AppController.capturingInput

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderPicker = FolderPicker(this)
        AppController.attach(this, folderPicker)
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
                    Screen.Game ->
                        GameScreen(
                            promptOpen = AppController.promptOpen,
                            onOpenPrompt = AppController::openPrompt,
                            onResume = AppController::resumeFromPrompt,
                            onQuit = AppController::quitFromPrompt,
                        )
                    Screen.Settings ->
                        SettingsScreen(
                            focusEntry = AppController.settingsEntry,
                            coreReady = AppController.settingsReady,
                            onOpen = AppController::openSettingsPage,
                            onBack = AppController::closeSettings,
                        )
                    Screen.Graphics -> {
                        val graphics = AppController.settingsPages.graphics
                        if (graphics == null) {
                            SettingsLoadingPage("Graphics", onBack = AppController::closeSettingsPage)
                        } else {
                            GraphicsScreen(
                                settings = graphics,
                                aspectNames = AppController.settingsPages.aspectRatioNames,
                                vulkanFailed = AppController.settingsPages.vulkanFailed,
                                onChange = AppController.settingsPages::changeGraphics,
                                onBack = AppController::closeSettingsPage,
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
                        val state = AppController.controllersPage.state
                        val bindings = state.bindings
                        if (bindings == null) {
                            SettingsLoadingPage("Controllers", onBack = AppController::closeSettingsPage)
                        } else {
                            ControllersScreen(
                                controllers = state.controllers,
                                labels = state.labels,
                                selected = state.selected,
                                bindings = bindings,
                                bindingsFor = state.bindingsFor,
                                capturing = state.capturing,
                                secondsLeft = state.secondsLeft,
                                undoFor = state.undo?.name,
                                onSelect = AppController::selectController,
                                onCapture = AppController::captureBinding,
                                onCancelCapture = AppController::cancelCapture,
                                onClear = AppController::clearBinding,
                                onUndo = AppController.controllersPage::undo,
                                onDropUndo = AppController.controllersPage::dropUndo,
                                onReset = AppController::resetBindings,
                                onBack = AppController::closeSettingsPage,
                            )
                        }
                    }
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
                            focusPath = AppController.lastGamePath,
                            focusSettings = AppController.focusSettings,
                        )
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
        if (!hasFocus && inGame) AppController.controllers?.releaseAll()
    }

    override fun onDestroy() {
        // Not an old activity destroyed after its replacement started: the game would be the new one's.
        if (isFinishing && inGame && AppController.isCurrent(this)) AppController.leaveGame()
        AppController.detach(this)
        super.onDestroy()
    }

    // With padInput, controllers go to the core; otherwise they move Compose's focus. A key-up goes where its key-down
    // went, either way, and a stick the core was given stays its until it's back near rest.
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
