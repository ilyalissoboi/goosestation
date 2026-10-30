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
}
