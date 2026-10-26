// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.NativeCallbacks
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import io.github.ilyalissoboi.goosestation.core.Resources
import io.github.ilyalissoboi.goosestation.input.ControllerInput
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import io.github.ilyalissoboi.goosestation.ui.GameListScreen
import io.github.ilyalissoboi.goosestation.ui.GameScreen
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var games by mutableStateOf(emptyList<File>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, whatever the system's theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        App.start(applicationContext)

        setContent {
            GooseStationTheme {
                if (App.playing != null) {
                    GameScreen(onBack = App::leaveGame)
                } else {
                    GameListScreen(
                        games = games,
                        gamesFolder = App.gamesFolder(this),
                        ready = App.ready && !App.gameActive,
                        message = App.message,
                        isError = App.messageIsError,
                        onStartGame = App::startGame,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        App.resumed = true
        if (App.playing != null && App.gameRunning) NativeLibrary.setPaused(false)
        refreshGames()
    }

    override fun onPause() {
        App.resumed = false
        if (App.playing != null) {
            App.controllers?.releaseAll()
            NativeLibrary.setPaused(true)
        }
        super.onPause()
    }

    // Losing focus (the notification shade, a dialog) sends a cancelled key-up for held keys, but nothing for axes.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && App.playing != null) App.controllers?.releaseAll()
    }

    override fun onDestroy() {
        if (isFinishing && App.playing != null) App.leaveGame()
        super.onDestroy()
    }

    // On the game screen, controllers go to the core; in the list, they move Compose's focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val controllers = App.controllers
        if (App.playing != null && controllers != null && controllers.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val controllers = App.controllers
        if (App.playing != null && controllers != null && controllers.onMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun refreshGames() {
        val files = App.gamesFolder(this).listFiles().orEmpty()
        games = files.filter { it.isFile && isLoadable(it.name) }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun isLoadable(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return LOADABLE_EXTENSIONS.any { lower.endsWith(it) }
    }

    private companion object {
        // As System::IsLoadableFilename() in the core.
        val LOADABLE_EXTENSIONS =
            listOf(
                ".bin", ".cue", ".img", ".iso", ".chd", ".ecm", ".mds",
                ".exe", ".psexe", ".ps-exe",
                ".psf", ".minipsf",
                ".m3u",
                ".pbp",
            )
    }
}

/**
 * The core and what the app shows, for the life of the process, so a recreated activity carries on. Main thread only,
 * except the [NativeCallbacks], which the core calls on its threads: they post to the main thread and never wait for
 * it, since it may be waiting for the core in [NativeLibrary.surfaceChanged].
 */
private object App : NativeCallbacks {
    private const val TAG = "GooseStation"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false

    /** The core is initialized: games can start, and the game screen can show. */
    var ready by mutableStateOf(false)
        private set

    /** The latest error, or what the core is doing; one at a time. */
    var message by mutableStateOf<String?>("Starting the core…")
        private set
    var messageIsError by mutableStateOf(false)
        private set

    /** The game the game screen is for, or null on the list. */
    var playing by mutableStateOf<File?>(null)
        private set

    /**
     * A game from startGame() isn't over yet: until its onGameStopped(), which always comes. No other game starts
     * meanwhile, so every callback is for this one, even after the user has left it.
     */
    var gameActive by mutableStateOf(false)
        private set

    /** The core has a game running, from onGameStarted to onGameStopped. */
    var gameRunning = false
        private set

    var resumed = false
    var controllers: ControllerInput? = null
        private set

    fun gamesFolder(context: Context) = File(context.getExternalFilesDir(null), "games")

    // Off the main thread: the resources are copied out, and initialize() waits for the core's thread to start.
    fun start(context: Context) {
        if (started) return
        started = true
        // The core calls it for content:// paths from its first use on.
        ContentBridge.init(context)

        thread(name = "GooseStation startup") {
            val result =
                runCatching {
                    val dataRoot = File(context.getExternalFilesDir(null), "data")
                    File(dataRoot, "bios").mkdirs()
                    gamesFolder(context).mkdirs()
                    val resources = Resources.extract(context)
                    NativeLibrary.setCallbacks(this)
                    NativeLibrary.initialize(dataRoot.absolutePath, resources.absolutePath)
                }

            mainHandler.post {
                val exception = result.exceptionOrNull()
                if (result.getOrDefault(false)) {
                    Log.i(TAG, "The core is ready.")
                    ready = true
                    if (!messageIsError) message = null
                    // Reported once the core takes input; it keeps connections made before then anyway.
                    controllers = ControllerInput(context).also { it.start() }
                } else {
                    Log.e(TAG, "The core failed to start.", exception)
                    // initialize() reported why through onError(), which was posted before this.
                    if (!messageIsError || exception != null) {
                        showError("The core failed to start: ${exception?.message ?: "unknown error"}")
                    }
                }
            }
        }
    }

    fun startGame(game: File) {
        if (!ready || gameActive) return
        message = null
        messageIsError = false
        gameActive = true
        playing = game
        // It boots once the game screen's surface exists.
        NativeLibrary.startGame(game.absolutePath)
    }

    // Back to the list, which waits for the game's onGameStopped() before it starts another.
    fun leaveGame() {
        playing = null
        if (!messageIsError) message = "Stopping the game…"
        NativeLibrary.shutdownGame()
    }

    private fun showError(text: String) {
        message = text
        messageIsError = true
    }

    override fun onGameStarted(title: String) {
        mainHandler.post {
            Log.i(TAG, "Game started: $title")
            gameRunning = true
            // Release on this thread, after any input it already sent during the boot, so nothing pressed
            // while booting can stay held.
            controllers?.releaseAll()
            // Booted in the background. One the user left while it booted is shut down next anyway.
            if (playing != null && !resumed) NativeLibrary.setPaused(true)
        }
    }

    override fun onGameStopped() {
        mainHandler.post {
            Log.i(TAG, "Game stopped.")
            gameRunning = false
            // Same as on start: release in order with this thread's input.
            controllers?.releaseAll()
            gameActive = false
            playing = null
            if (!messageIsError) message = null
        }
    }

    override fun onError(message: String) {
        mainHandler.post {
            Log.w(TAG, "Error: $message")
            // A failed boot can report twice; the latest one shows. onGameStopped() follows a failed boot, and takes the
            // app back to the list, where it shows.
            showError(message)
        }
    }
}
