// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/** When a game runs rather than stays paused. Everything that unpauses one asks this first. */
object PausePolicy {
    /** Only with the app in front ([resumed]) and the game's menu closed. */
    fun running(
        resumed: Boolean,
        menuOpen: Boolean,
    ): Boolean = resumed && !menuOpen

    /**
     * What a game that has just booted is set to: paused (true) if it may not run, unpaused (false) if it may but the
     * app paused it while it booted (that pause can reach the core just after the boot), or left as it booted (null),
     * which keeps desktop's StartPaused. [running] is [PausePolicy.running] now.
     */
    fun atBoot(
        pausedDuringBoot: Boolean,
        running: Boolean,
    ): Boolean? =
        when {
            !running -> true
            pausedDuringBoot -> false
            else -> null
        }

    /**
     * Whether a game that has just booted opens its menu: with `[Main] StartPaused` it boots paused, and one left as
     * it booted ([atBoot] null, [PausePolicy.atBoot]'s) would show a still game with no way on but Back.
     */
    fun opensMenuAtBoot(
        startPaused: Boolean,
        atBoot: Boolean?,
    ): Boolean = startPaused && atBoot == null

    /**
     * Whether a controller going opens the game's menu: with `[Main] PauseOnControllerDisconnection` ([setting]), while
     * the game runs on the game screen with the menu closed. Every controller drives the game, so each has bindings.
     */
    fun pausesOnDisconnect(
        setting: Boolean,
        gameRunning: Boolean,
        onGameScreen: Boolean,
        menuOpen: Boolean,
    ): Boolean = setting && gameRunning && onGameScreen && !menuOpen
}
