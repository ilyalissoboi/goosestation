// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/** When a game runs rather than stays paused. Everything that unpauses one asks this first. */
object PausePolicy {
    /** Only with the app in front ([resumed]) and the game's prompt closed. */
    fun running(
        resumed: Boolean,
        promptOpen: Boolean,
    ): Boolean = resumed && !promptOpen

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
}
