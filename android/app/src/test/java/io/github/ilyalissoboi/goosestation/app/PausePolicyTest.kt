// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A game runs only while the app is in front with the prompt closed. */
class PausePolicyTest {
    @Test
    fun runsWhenResumedWithThePromptClosed() {
        assertTrue(PausePolicy.running(resumed = true, promptOpen = false))
    }

    @Test
    fun staysPausedWithThePromptOpen() {
        assertFalse(PausePolicy.running(resumed = true, promptOpen = true))
    }

    @Test
    fun staysPausedInTheBackground() {
        assertFalse(PausePolicy.running(resumed = false, promptOpen = false))
    }

    @Test
    fun aGamePausedByTheAppDuringItsBootIsUnpausedIfItMayRun() {
        // The prompt opened, then Resume, while it booted: the prompt's pause reached the core after the boot.
        assertEquals(false, PausePolicy.atBoot(pausedDuringBoot = true, running = true))
    }

    @Test
    fun aGameTheAppDidntPauseIsLeftAsItBooted() {
        // No unpause call, so desktop's StartPaused = true still holds.
        assertNull(PausePolicy.atBoot(pausedDuringBoot = false, running = true))
    }

    @Test
    fun aGameThatMayNotRunIsPausedAtBoot() {
        assertEquals(true, PausePolicy.atBoot(pausedDuringBoot = false, running = false))
        assertEquals(true, PausePolicy.atBoot(pausedDuringBoot = true, running = false))
    }

    @Test
    fun startPausedOpensTheMenu() {
        assertTrue(PausePolicy.opensMenuAtBoot(startPaused = true, atBoot = null))
    }

    @Test
    fun startPausedLeavesABootTheAppPausedAlone() {
        // In the background, or with the prompt open: paused for that, with its own way back.
        assertFalse(PausePolicy.opensMenuAtBoot(startPaused = true, atBoot = true))
        assertFalse(PausePolicy.opensMenuAtBoot(startPaused = true, atBoot = false))
    }

    @Test
    fun noMenuWithoutStartPaused() {
        assertFalse(PausePolicy.opensMenuAtBoot(startPaused = false, atBoot = null))
    }

    @Test
    fun aDisconnectPausesARunningGame() {
        assertTrue(
            PausePolicy.pausesOnDisconnect(setting = true, gameRunning = true, onGameScreen = true, promptOpen = false),
        )
    }

    @Test
    fun noPauseWhileTheMenuIsOpen() {
        assertFalse(
            PausePolicy.pausesOnDisconnect(setting = true, gameRunning = true, onGameScreen = true, promptOpen = true),
        )
    }

    @Test
    fun noPauseWhileBootingOrOffTheGame() {
        assertFalse(
            PausePolicy.pausesOnDisconnect(
                setting = true,
                gameRunning = false,
                onGameScreen = true,
                promptOpen = false,
            ),
        )
        assertFalse(
            PausePolicy.pausesOnDisconnect(
                setting = true,
                gameRunning = true,
                onGameScreen = false,
                promptOpen = false,
            ),
        )
    }

    @Test
    fun noPauseWithTheSettingOff() {
        assertFalse(
            PausePolicy.pausesOnDisconnect(
                setting = false,
                gameRunning = true,
                onGameScreen = true,
                promptOpen = false,
            ),
        )
    }
}
