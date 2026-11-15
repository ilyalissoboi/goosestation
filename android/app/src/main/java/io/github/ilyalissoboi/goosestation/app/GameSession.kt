// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.GameEntry
import kotlinx.coroutines.CoroutineScope

/**
 * A game, from its start to the core saying it stopped: whether a start goes ahead, whether the game runs or stays
 * paused, its menu, and what a stop leaves behind. One game at a time. Main thread only, every field and function:
 * the core's own events ([started], [stopped]) are posted to it before they call in.
 */
class GameSession(
    scope: CoroutineScope,
    /** What a start waits for: the settings work asked for before it. */
    settle: suspend () -> Unit,
    private val core: SessionCore,
    private val hooks: SessionHooks,
    private val status: StatusLine,
) {
    private val startGate = GameStartGate(scope, settle)

    /** The game to start from a state once the running one has stopped, as the in-game menu asked. */
    private val gameSwitch = GameSwitch(scope, hooks::startNext)

    /**
     * A game from a start isn't over yet: until its [stopped], which always comes. No other game starts meanwhile, so
     * every event from the core is for this one, even after the user has left it. The UI reads it.
     */
    var gameActive by mutableStateOf(false)
        private set

    /** The core has a game running, from [started] to [stopped]. */
    var gameRunning = false
        private set

    /** The game from the last start, until its [stopped]. */
    var runningGame: GameEntry? = null
        private set

    /** The last game started, which the library focuses on coming back to it. The UI reads it. */
    var lastGamePath by mutableStateOf<String?>(null)
        private set

    /**
     * The in-game menu shows over the game: the game is paused, and the controllers move the menu's focus rather than
     * play. The UI reads it.
     */
    var menuOpen: Boolean
        get() = menuShown
        private set(value) {
            menuShown = value
            // Unlock cards wait, and the live indicators hide, while the menu is open.
            hooks.menuShown(value)
        }
    private var menuShown by mutableStateOf(false)

    /** The app is in front, between the activity's resume and its pause. */
    var resumed = false
        private set

    /**
     * The app paused the game while it booted ([pause]). That pause can reach the core just after the boot, so
     * [started] unpauses the game if it may run by then. Reset as the game starts or stops.
     */
    private var pausedDuringBoot = false

    /** The running game pauses into its menu when a controller disconnects: its `[Main] PauseOnControllerDisconnection`. */
    private var pausesOnDisconnect = false

    /** A start is waiting for its settings: another is ignored meanwhile. */
    val starting get() = startGate.starting

    // --- Starts ---

    /** Starts [game] on its [disc], or from its resume state. */
    fun start(
        game: GameEntry,
        disc: Int,
        resume: Boolean,
    ) {
        // Once the settings asked for before it have landed; it boots once the game screen's surface exists.
        startGate.start(begin = { begin(game) }, boot = { core.startGame(game.path, disc, resume) })
    }

    /** Starts [game] from [state] (a save on the Saves page, or a shared slot of another game), as [start] starts. */
    fun startFromState(
        game: GameEntry,
        state: StateRef,
    ) {
        startGate.start(begin = { begin(game) }, boot = { core.startGameFromState(game.path, state) })
    }

    // Both starts, checked again: things may have changed while the player answered, or while a switch to another
    // game waited for the last one's cards. A start goes from the library or the Saves page, where it's asked for, and
    // never during a data folder change; one that finds the player elsewhere by then says so rather than start over
    // the page. True when the game screen is up for the core to boot on.
    private fun begin(game: GameEntry): Boolean {
        if (!hooks.ready || gameActive) return false
        if (!hooks.startsAllowed) {
            status.show(Status.Copying, COPYING)
            return false
        }
        if (hooks.dataChanging || (hooks.screen != Screen.Library && hooks.screen != Screen.Saves)) {
            status.show(Status.Note, "${game.title} didn't start. Start it again from the library.")
            return false
        }
        lastGamePath = game.path
        runningGame = game
        status.clear()
        gameActive = true
        hooks.onBegin(game)
        hooks.screen = Screen.Game
        return true
    }

    /** The running game is being left for [to], a shared slot of another game: it starts once this one has stopped. */
    fun leavingFor(to: StateStart) = gameSwitch.leaving(to)

    // --- The menu, and pausing ---

    /** Back to the library, which waits for the game's [stopped] before it starts another. */
    fun leave() {
        menuOpen = false
        hooks.screen = Screen.Library
        status.progress(Status.Stopping, "Stopping the game…")
        core.shutdownGame()
    }

    /**
     * Pauses the game and shows its menu, from Back, Mode or Select + Start held. Everything the controllers hold is
     * let go first, so nothing pressed now is still pressed after Resume. The pause writes the memory cards. A game
     * still booting is paused once it has booted ([started]).
     */
    fun openMenu() {
        if (hooks.screen != Screen.Game || menuOpen) return
        menuOpen = true
        hooks.menuOpened()
        hooks.releaseInput()
        hooks.stopRumble()
        pause()
    }

    /** The controller called [name] went: with PauseOnControllerDisconnection, the game pauses into its menu, as Back does. */
    fun controllerDisconnected(name: String) {
        val onGameScreen = hooks.screen == Screen.Game
        if (!PausePolicy.pausesOnDisconnect(pausesOnDisconnect, gameRunning, onGameScreen, menuOpen)) return
        openMenu()
        hooks.say("$name disconnected")
    }

    /** Pauses the game, which writes its memory cards: for the menu, or the app going to the background. */
    fun pause() {
        if (gameActive && !gameRunning) pausedDuringBoot = true
        core.setPaused(true)
    }

    /** Closes the menu, and carries on with the game if the app is in front ([PausePolicy]). */
    fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        // As the menu opened: an axis the core still had, such as a trigger let go once the stick it came with was
        // back at rest, isn't left held in the game.
        hooks.releaseInput()
        if (gameRunning && PausePolicy.running(resumed, menuOpen = false)) core.setPaused(false)
    }

    /** Closes the menu, and stops the game: back to the library. */
    fun exitToLibrary() {
        if (!menuOpen) return
        menuOpen = false
        leave()
    }

    /** Exit game, once the menu has saved the resume state, or failed to ([InGameMenu.exit]): [resumeError] says why. */
    fun exit(resumeError: String?) {
        exitToLibrary()
        resumeError?.let { status.error("Couldn't save your place: $it") }
    }

    // --- The app in front, and behind ---

    /** The app is in front: a game on screen carries on, unless its menu is open ([PausePolicy]). */
    fun onResume() {
        resumed = true
        // A game left paused under its menu stays so.
        if (hooks.screen == Screen.Game && gameRunning && PausePolicy.running(resumed, menuOpen)) {
            core.setPaused(false)
        }
    }

    /** The app is no longer in front: a game on screen lets go of what's held and pauses, which writes its cards. */
    fun onPause() {
        resumed = false
        if (hooks.screen == Screen.Game) {
            hooks.releaseInput()
            hooks.releaseTouch()
            pause()
        }
    }

    /**
     * The volume the game, or its data folder, is on is going away: its memory cards are written and it's stopped,
     * closing its disc. [stopped] follows.
     */
    fun stopForEject() {
        // The shutdown saves the memory cards anyway, before it closes the disc. Pausing writes them at once
        // (android_host_boot.cpp), before anything else the core runs.
        core.setPaused(true)
        if (hooks.screen == Screen.Game) hooks.screen = Screen.Library
        core.shutdownGame()
    }

    // --- The core's events ---

    /**
     * The core booted the game, as [title] and [serial]: [startPaused] and [pauseOnDisconnect] are its `[Main]`
     * settings.
     */
    fun started(
        title: String,
        serial: String,
        startPaused: Boolean,
        pauseOnDisconnect: Boolean,
    ) {
        pausesOnDisconnect = pauseOnDisconnect
        gameRunning = true
        // The game as the library listed it; one without a key (not in the list) is keyed by the core's ID, as the
        // host keys it.
        val game = runningGame ?: GameEntry("", title, serial, "", 0, 0, null)
        hooks.onStarted(if (game.serial.isBlank()) game.copy(serial = serial) else game)
        // Release on this thread, after any input it already sent during the boot, so nothing pressed
        // while booting can stay held.
        hooks.releaseInput()
        // Booted in the background or with the menu open, it waits paused. One the app paused while it booted
        // runs if it may by now, as that pause may have reached the core just after the boot. One the user left
        // while it booted is shut down next anyway.
        val paused = PausePolicy.atBoot(pausedDuringBoot, PausePolicy.running(resumed, menuOpen))
        pausedDuringBoot = false
        if (hooks.screen == Screen.Game && paused != null) core.setPaused(paused)
        // Booted paused, with nothing on screen to resume from: the game's menu is that.
        if (hooks.screen == Screen.Game && PausePolicy.opensMenuAtBoot(startPaused, paused)) openMenu()
        // The cards its own settings name are held back from now on, and mirrored.
        hooks.readCorePaths()
    }

    /** The core stopped the game: the session is over, and what waited for that goes ahead. */
    fun stopped() {
        gameRunning = false
        pausedDuringBoot = false
        pausesOnDisconnect = false
        // Same as on start: release in order with this thread's input.
        hooks.releaseInput()
        menuOpen = false
        gameActive = false
        runningGame = null
        hooks.onStopped()
        if (hooks.screen == Screen.Game) hooks.screen = Screen.Library
        // A start refused meanwhile, while a catch-up runs, still says why.
        if (!status.isError && !hooks.keepsCopyingLine()) status.clear()
        // The memory cards held back while it ran come in now, then the game's own writes go out; a game the menu
        // left for starts after that, on the cards as they are now.
        gameSwitch.stopped { hooks.syncAfterStop() }
    }
}
