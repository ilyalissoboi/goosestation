// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.NativeLibrary

/**
 * The core's calls a game's session makes ([GameSession]), and no others: [NativeSessionCore] in the app, a fake in a
 * JVM test. Each is called on the main thread, and none waits for the core.
 */
interface SessionCore {
    fun startGame(
        path: String,
        disc: Int,
        resume: Boolean,
    )

    fun startGameFromState(
        path: String,
        state: StateRef,
    )

    fun shutdownGame()

    fun setPaused(paused: Boolean)
}

/** [SessionCore] on [NativeLibrary]. */
object NativeSessionCore : SessionCore {
    override fun startGame(
        path: String,
        disc: Int,
        resume: Boolean,
    ) = NativeLibrary.startGame(path, disc, resume)

    override fun startGameFromState(
        path: String,
        state: StateRef,
    ) = NativeLibrary.startGameFromState(path, state.key, state.slot, state.shared)

    override fun shutdownGame() = NativeLibrary.shutdownGame()

    override fun setPaused(paused: Boolean) = NativeLibrary.setPaused(paused)
}

/** What a game's session ([GameSession]) asks the app, or tells it. On the main thread. */
interface SessionHooks {
    /** The core is initialized, and the data folder pulled in full: games can start. */
    val ready: Boolean

    /** No catch-up pull is under way: a start meanwhile would boot on cards still coming in. */
    val startsAllowed: Boolean

    /** A data folder change is under way, which ends in a restart. */
    val dataChanging: Boolean

    /** The screen shown. The session sets only [Screen.Game] and [Screen.Library]. */
    var screen: Screen

    /** Whether the status line is a refused start's "Copying…", which still says why once the game has stopped. */
    fun keepsCopyingLine(): Boolean

    /**
     * [game] is about to take the screen: the library's focus and search are reset, its memory cards are held back
     * from the mirror, a cover download stops, and the Saves page it may have started from closes.
     */
    fun onBegin(game: GameEntry)

    /** The core has booted [game]: the menu, the touch controls and the achievements' messages start with it. */
    fun onStarted(game: GameEntry)

    /** The core has stopped the game: what showed over it, its menu and what it held are cleared. */
    fun onStopped()

    /** The menu shows over the game, or no longer does: achievement cards wait while it's [open]. */
    fun menuShown(open: Boolean)

    /** The menu has just opened: it reads what it shows. */
    fun menuOpened()

    /** Everything the controllers hold is let go, so nothing pressed now stays pressed in the game. */
    fun releaseInput()

    /** Everything the touch controls hold is let go. */
    fun releaseTouch()

    fun stopRumble()

    /** A message over the game. */
    fun say(text: String)

    /** The game has booted: the memory cards its own settings name are read, to be held back and mirrored. */
    fun readCorePaths()

    /** After a stop: the cards held back while the game ran come in, then the game's own writes go out. */
    suspend fun syncAfterStop()

    /** The game the in-game menu left for, from its shared slot, now that the last one has stopped and synced. */
    fun startNext(next: StateStart)
}
