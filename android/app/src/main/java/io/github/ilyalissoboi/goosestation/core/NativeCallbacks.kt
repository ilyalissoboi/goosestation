// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * Events from the core, registered with [NativeLibrary.setCallbacks]. They're called on the core's threads, not the
 * UI's: post to the UI thread, and never wait for it (it may be waiting for the core in [NativeLibrary.surfaceChanged]).
 */
interface NativeCallbacks {
    /**
     * The game from [NativeLibrary.startGame] booted: its [title], its [serial] (blank for none), and whether it
     * [startPaused] and pauses when a controller disconnects ([pauseOnDisconnect]), as the core has them for it.
     */
    fun onGameStarted(
        title: String,
        serial: String,
        startPaused: Boolean,
        pauseOnDisconnect: Boolean,
    )

    /**
     * The game from [NativeLibrary.startGame] is gone. Every startGame() ends with exactly one call: when the game
     * stops, or without [onGameStarted] when it fails to boot (after [onError]), is refused, or is cancelled before it
     * boots.
     */
    fun onGameStopped()

    /** Something to show the user. A failed boot can report more than one; it doesn't end the game by itself. */
    fun onError(message: String)

    /**
     * The input [NativeLibrary.startBindingCapture] captured, as a [PadBinding.binding] for [NativeLibrary.setPadBinding]:
     * Button99, +Axis23, -Axis0. Each capture ends with at most one call, and none once it's cancelled or replaced.
     */
    fun onBindingCaptured(binding: String) {}

    /**
     * An on-screen message from the core: its [text] (icon characters gone), shown for [seconds], replacing any with
     * the same [key] (empty for none), and its [icon], if it has one. [seconds] 0 takes [key]'s message away.
     */
    fun onOsdMessage(
        key: String,
        text: String,
        seconds: Float,
        icon: ArgbImage?,
    ) {}

    /** Fast forward was turned on or off, by the menu or a hotkey. */
    fun onFastForwardChanged(enabled: Boolean) {}

    /**
     * The RetroAchievements login token changed: a sign-in's new [token], or null when it's gone (signed out, or
     * rejected). Keep it before returning: the core keeps it in memory only.
     */
    fun onAchievementsToken(token: String?) {}

    /**
     * Memory card sync's secret [name] (`s3-secret-access-key` or `encryption-passphrase`) is now [value], or gone
     * with null. Keep it in the Keystore before returning, and say whether that worked.
     */
    fun storeMemcardSyncSecret(
        name: String,
        value: String?,
    ): Boolean = false

    /** Memory card sync's secret [name], or null for none. */
    fun loadMemcardSyncSecret(name: String): String? = null

    /** Whether memory card sync's secret [name] is stored, without reading it. */
    fun hasMemcardSyncSecret(name: String): Boolean = false

    /** Memory card sync's state may have changed: read it again ([NativeLibrary.getMemcardSyncState]). */
    fun onMemcardSyncChanged() {}

    /** The RetroAchievements sign-in, the running game's set, or hardcore mode changed: read them again. */
    fun onAchievementsChanged() {}

    /** The server rejected the stored token: the player is signed out and must sign in again. */
    fun onAchievementsLoginExpired() {}

    /** Every unlock and leaderboard entry the player had waiting has been sent: [count] of them. */
    fun onAchievementsSent(count: Int) {}

    /** The server refused a waiting unlock or entry, called [title], which is dropped. */
    fun onAchievementRefused(title: String) {}

    /**
     * An achievement notice to show: its [key] says what it is (`achievement_unlock_<id>`, `achievement_mastery`,
     * `achievement_summary`, `leaderboard_<id>`), with its [title], [text], picture ([imagePath], a PNG, or empty),
     * how long it shows, an unlock's [points] (-1 otherwise), and whether an unlock is [offline]: earned without a
     * connection, so sent later.
     */
    fun onAchievementNotification(
        key: String,
        title: String,
        text: String,
        imagePath: String,
        seconds: Float,
        points: Int,
        offline: Boolean,
    ) {}

    /** A short achievements message, such as losing the connection: [title], or [message] if it has none. */
    fun onAchievementToast(
        title: String,
        message: String,
        seconds: Float,
    ) {}

    /**
     * A live indicator during play ([kind] 0 a leaderboard tracker, 1 a challenge, 2 the progress pop-up) shows or
     * changes ([shown]) or goes. [id] is the tracker's or the achievement's, [text] the tracker's value or the
     * progress, [badgePath] the achievement's badge.
     */
    fun onAchievementIndicator(
        kind: Int,
        id: Int,
        shown: Boolean,
        text: String,
        badgePath: String,
    ) {}

    /** A badge or icon downloaded to [path]: anything showing it without its picture can load it now. */
    fun onAchievementImage(path: String) {}

    /** One of the core's sounds (a WAV at [path]) to play now. */
    fun onPlaySound(path: String) {}

    /** [NativeLibrary.fetchLeaderboard]'s answer to [request]: the leaderboard's [page], or why there's none ([error]). */
    fun onLeaderboard(
        request: Int,
        page: LeaderboardPage?,
        error: String?,
    ) {}
}
