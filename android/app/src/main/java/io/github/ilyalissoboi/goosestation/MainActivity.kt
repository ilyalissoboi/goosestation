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
import io.github.ilyalissoboi.goosestation.app.Screen
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import io.github.ilyalissoboi.goosestation.storage.FolderPicker
import io.github.ilyalissoboi.goosestation.ui.FoldersPlaceholder
import io.github.ilyalissoboi.goosestation.ui.GameScreen
import io.github.ilyalissoboi.goosestation.ui.LibraryScreen
import io.github.ilyalissoboi.goosestation.ui.SetupScreen
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

class MainActivity : ComponentActivity() {
    // Registered here, before the activity starts, as the result API needs.
    private lateinit var folderPicker: FolderPicker

    private val inGame get() = AppController.screen == Screen.Game

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderPicker = FolderPicker(this)
        AppController.attach(folderPicker)
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
                    Screen.Game -> GameScreen(onBack = AppController::leaveGame)
                    Screen.Folders -> FoldersPlaceholder(onBack = AppController::closeFolders)
                    Screen.Library -> {
                        val mirrorState by AppController.mirrorState.collectAsState()
                        LibraryScreen(
                            games = AppController.games,
                            gridView = AppController.gridView,
                            banners = AppController.banners(mirrorState),
                            message = AppController.message,
                            isError = AppController.messageIsError,
                            onToggleView = AppController::toggleView,
                            onOpenFolders = AppController::openFolders,
                            onRefresh = AppController::refresh,
                            onStartGame = AppController::startGame,
                            focusPath = AppController.lastGamePath,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppController.onStart()
    }

    override fun onResume() {
        super.onResume()
        AppController.resumed = true
        if (inGame && AppController.gameRunning) NativeLibrary.setPaused(false)
    }

    override fun onPause() {
        AppController.resumed = false
        if (inGame) {
            AppController.controllers?.releaseAll()
            NativeLibrary.setPaused(true)
        }
        super.onPause()
    }

    override fun onStop() {
        AppController.onStop()
        super.onStop()
    }

    // Losing focus (the notification shade, a dialog) sends a cancelled key-up for held keys, but nothing for axes.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && inGame) AppController.controllers?.releaseAll()
    }

    override fun onDestroy() {
        if (isFinishing && inGame) AppController.leaveGame()
        AppController.detach(folderPicker)
        super.onDestroy()
    }

    // On the game screen, controllers go to the core; elsewhere, they move Compose's focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val controllers = AppController.controllers
        if (inGame && controllers != null && controllers.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val controllers = AppController.controllers
        if (inGame && controllers != null && controllers.onMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}
