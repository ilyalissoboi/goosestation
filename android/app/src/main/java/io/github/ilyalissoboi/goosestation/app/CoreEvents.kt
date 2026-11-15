// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.LeaderboardPage
import io.github.ilyalissoboi.goosestation.core.NativeCallbacks
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import io.github.ilyalissoboi.goosestation.input.PadKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** What the core's events reach on the main thread, once [CoreEvents] has posted them there. Main thread only. */
interface MainEvents {
    /** The game booted ([NativeCallbacks.onGameStarted]). */
    fun gameStarted(
        title: String,
        serial: String,
        startPaused: Boolean,
        pauseOnDisconnect: Boolean,
    )

    /** The game is gone ([NativeCallbacks.onGameStopped]). */
    fun gameStopped()

    /** Something went wrong, to show the user. */
    fun error(message: String)

    /** Shows [text] over the game for [seconds], in place of [key]'s message if there is one, with its [icon]. */
    fun showMessage(
        text: String,
        key: String? = null,
        seconds: Float? = null,
        icon: ArgbImage? = null,
    )

    /** Takes [key]'s message away. */
    fun removeMessage(key: String)

    fun memcardSyncChanged()

    /** The server rejected the stored token: the player is signed out. */
    fun achievementsLoginExpired()

    /** The sign-in, the running game's set or hardcore mode changed. */
    fun achievementsChanged()

    /** An achievement's card, over the game. */
    fun achievementCard(card: AchievementNotice.Card)

    fun achievementIndicator(
        kind: Int,
        id: Int,
        shown: Boolean,
        text: String,
        badgePath: String,
    )

    fun leaderboard(
        request: Int,
        page: LeaderboardPage?,
        error: String?,
    )

    fun achievementImage(path: String)

    fun fastForwardChanged(enabled: Boolean)

    /** Port 1's kind for the touch pad; null once the game ends, or for a pad it doesn't support. */
    fun padKindChanged(kind: PadKind?)

    fun hotkey(hotkey: StateHotkey)
}

/**
 * What the core's events reach on the core's thread, before the call returns: the core reads the token and the
 * secrets right after, and the capture, the rumble and the sound hand off on their own.
 */
interface CoreThreadEvents {
    /** Keeps the RetroAchievements login token, or forgets it with null. */
    fun storeAchievementsToken(token: String?)

    fun storeMemcardSyncSecret(
        name: String,
        value: String?,
    ): Boolean

    fun loadMemcardSyncSecret(name: String): String?

    fun hasMemcardSyncSecret(name: String): Boolean

    fun bindingCaptured(binding: String)

    fun rumble(
        deviceId: Int,
        large: Float,
        small: Float,
    )

    fun playSound(path: String)
}

/**
 * The core's [NativeCallbacks], which it calls on its own threads. Each event is posted to the main thread ([post]),
 * and never waits for it: the main thread may be waiting for the core in [NativeLibrary.surfaceChanged]. The few the
 * core needs done before it goes on ([CoreThreadEvents]) run on the calling thread instead. Nothing here touches the
 * app's state off the main thread.
 */
class CoreEvents(
    /** Runs a block on the main thread, later; never waits for it. */
    private val post: (() -> Unit) -> Unit,
    private val main: MainEvents,
    private val core: CoreThreadEvents,
    /** Main thread: where a notice's picture is decoded, off it, on [io]. */
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    /** A picture file, decoded at the size an on-screen message shows it; null if it can't be read. Blocks. */
    private val decodeIcon: (path: String) -> ArgbImage?,
) : NativeCallbacks {
    override fun onGameStarted(
        title: String,
        serial: String,
        startPaused: Boolean,
        pauseOnDisconnect: Boolean,
    ) = post { main.gameStarted(title, serial, startPaused, pauseOnDisconnect) }

    override fun onGameStopped() = post { main.gameStopped() }

    // A failed boot can report twice; the latest one shows. onGameStopped() follows a failed boot, and takes the app
    // back to the library, where it shows.
    override fun onError(message: String) = post { main.error(message) }

    override fun onBindingCaptured(binding: String) = core.bindingCaptured(binding)

    override fun onOsdMessage(
        key: String,
        text: String,
        seconds: Float,
        icon: ArgbImage?,
    ) = post {
        if (seconds == 0f) {
            main.removeMessage(key)
        } else {
            CoreMessages.translate(key, text)?.let { main.showMessage(it, key.ifEmpty { null }, seconds, icon) }
        }
    }

    // On the core's thread, before the core goes on: the token lives only in its memory until it's stored here.
    override fun onAchievementsToken(token: String?) = core.storeAchievementsToken(token)

    override fun storeMemcardSyncSecret(
        name: String,
        value: String?,
    ) = core.storeMemcardSyncSecret(name, value)

    override fun loadMemcardSyncSecret(name: String) = core.loadMemcardSyncSecret(name)

    override fun hasMemcardSyncSecret(name: String) = core.hasMemcardSyncSecret(name)

    override fun onMemcardSyncChanged() = post { main.memcardSyncChanged() }

    override fun onAchievementsLoginExpired() = post { main.achievementsLoginExpired() }

    override fun onAchievementsSent(count: Int) = post { main.showMessage(AchievementQueueWords.sent(count)) }

    override fun onAchievementRefused(title: String) = post { main.showMessage(AchievementQueueWords.refused(title)) }

    override fun onAchievementsChanged() = post { main.achievementsChanged() }

    override fun onAchievementNotification(
        key: String,
        title: String,
        text: String,
        imagePath: String,
        seconds: Float,
        points: Int,
        offline: Boolean,
    ) = post {
        when (val notice = AchievementNotices.from(key, title, text, imagePath, points, offline, seconds)) {
            is AchievementNotice.Card -> main.achievementCard(notice)
            is AchievementNotice.Message ->
                scope.launch {
                    val icon = notice.imagePath?.let { withContext(io) { decodeIcon(it) } }
                    main.showMessage(notice.text, key, seconds, icon)
                }
        }
    }

    override fun onAchievementToast(
        title: String,
        message: String,
        seconds: Float,
    ) = post { main.showMessage(AchievementNotices.toast(title, message), seconds = seconds) }

    override fun onAchievementIndicator(
        kind: Int,
        id: Int,
        shown: Boolean,
        text: String,
        badgePath: String,
    ) = post { main.achievementIndicator(kind, id, shown, text, badgePath) }

    override fun onPlaySound(path: String) = core.playSound(path)

    override fun onLeaderboard(
        request: Int,
        page: LeaderboardPage?,
        error: String?,
    ) = post { main.leaderboard(request, page, error) }

    override fun onAchievementImage(path: String) = post { main.achievementImage(path) }

    override fun onFastForwardChanged(enabled: Boolean) = post { main.fastForwardChanged(enabled) }

    override fun onPadKindChanged(kind: Int) = post { main.padKindChanged(PadKind.fromCore(kind)) }

    override fun onRumble(
        deviceId: Int,
        large: Float,
        small: Float,
    ) = core.rumble(deviceId, large, small)

    // The name is looked up here, so an unknown one posts nothing.
    override fun onHotkey(name: String) {
        val hotkey = StateHotkey.entries.firstOrNull { it.coreName == name } ?: return
        post { main.hotkey(hotkey) }
    }
}
