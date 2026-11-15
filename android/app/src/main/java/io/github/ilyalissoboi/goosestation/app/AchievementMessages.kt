// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameAchievements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** The two core reads [AchievementMessages] makes. Each blocks: call them off the main thread. */
interface AchievementsReader {
    /** How many unlocks and entries the signed-in player has waiting to be sent. */
    fun queuedOwn(): Int

    /** The running game's achievements, as the core has them now. */
    fun gameAchievements(): GameAchievements
}

/**
 * RetroAchievements' messages over the game, each said once: that the player was signed out, at the next game's
 * start, and that RetroAchievements couldn't be reached, or that the game started offline, once per game. Main thread
 * only; the core reads run on [core] and [game].
 */
class AchievementMessages(
    private val scope: CoroutineScope,
    /** Where the queue is read: the core's library and settings calls. */
    private val core: CoroutineContext,
    /** Where the game's achievements are read: the in-game menu's calls. */
    private val game: CoroutineContext,
    private val reader: AchievementsReader,
    /** A game is running, from the core's start to its stop. */
    private val gameRunning: () -> Boolean,
    private val say: (String) -> Unit,
) {
    // The server rejected the stored token: said at the next game's start, where the player sees it.
    private var signInExpired = false

    // This game's "couldn't reach" or "offline" has been said.
    private var unreachableSaid = false

    /** The player was signed out ([io.github.ilyalissoboi.goosestation.core.NativeCallbacks.onAchievementsLoginExpired]). */
    fun loginExpired() {
        signInExpired = true
    }

    /** A game started: its own "couldn't reach" may be said, and a sign-in that expired since the last one is. */
    fun gameStarted() {
        unreachableSaid = false
        if (signInExpired) {
            signInExpired = false
            // With what it keeps for when the player signs in again.
            scope.launch {
                val kept = withContext(core) { reader.queuedOwn() }
                say(AchievementQueueWords.expiry(kept))
            }
        }
    }

    /**
     * The sign-in, the game's set or hardcore mode changed: with a game running, says once if RetroAchievements
     * couldn't be reached for it, or if it started offline.
     */
    fun achievementsChanged() {
        if (!gameRunning() || unreachableSaid) return
        scope.launch {
            val achievements = withContext(game) { reader.gameAchievements() }
            if (!gameRunning() || unreachableSaid) return@launch
            when {
                achievements.status == GameAchievements.UNREACHABLE -> {
                    unreachableSaid = true
                    say(UNREACHABLE)
                }
                achievements.status == GameAchievements.READY && achievements.offline -> {
                    unreachableSaid = true
                    say(AchievementQueueWords.OFFLINE_START)
                }
            }
        }
    }

    companion object {
        const val UNREACHABLE = "Couldn't reach RetroAchievements; achievements are off for this game"
    }
}
