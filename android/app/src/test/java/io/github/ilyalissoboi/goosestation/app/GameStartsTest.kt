// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.GameEntry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Starting a game: at once, or after asking whether to resume where it was left. */
class GameStartsTest {
    private val calls = mutableListOf<String>()
    private val api = FakeGameCore(calls)
    private val picture = ArgbImage(1, 1, intArrayOf(-1))
    private val crash = GameEntry("content://games/crash.cue", "Crash Bandicoot", "SCUS-94900", "NTSC-U", 0, 0, null)
    private val exe = GameEntry("content://games/padtest.psexe", "padtest", "", "", 0, 0, null)

    private fun TestScope.starts(): GameStarts {
        val starts =
            GameStarts(backgroundScope, StandardTestDispatcher(testScheduler), api) { game, resume ->
                calls += "start ${game.title} $resume"
            }
        calls.clear()
        return starts
    }

    @Test
    fun noResumeStateStartsAtOnce() =
        runTest {
            val starts = starts()

            starts.play(crash)
            runCurrent()

            assertEquals(listOf("getSaveStateTimes SCUS-94900", "start Crash Bandicoot null"), calls)
            assertNull(starts.offer)
        }

    @Test
    fun aResumeStateIsOffered() =
        runTest {
            api.times[SaveSlots.RESUME] = 1_700_000_000
            api.screenshots[SaveSlots.RESUME] = picture
            val starts = starts()

            starts.play(crash)
            runCurrent()

            val offer = starts.offer!!
            assertSame(crash, offer.game)
            assertEquals(1_700_000_000L, offer.savedAt)
            assertSame(picture, offer.picture)
            assertEquals(listOf("getSaveStateTimes SCUS-94900", "getSaveStateScreenshot SCUS-94900 -1"), calls)
        }

    @Test
    fun resumeBootsFromIt() =
        runTest {
            api.times[SaveSlots.RESUME] = 1_700_000_000
            val starts = starts()
            starts.play(crash)
            runCurrent()
            calls.clear()

            starts.resume()

            assertEquals(listOf("start Crash Bandicoot SCUS-94900"), calls)
            assertNull(starts.offer)
        }

    @Test
    fun startOverBootsNormally() =
        runTest {
            api.times[SaveSlots.RESUME] = 1_700_000_000
            val starts = starts()
            starts.play(crash)
            runCurrent()
            calls.clear()

            starts.startOver()

            assertEquals(listOf("start Crash Bandicoot null"), calls)
            assertNull(starts.offer)
        }

    @Test
    fun cancelStartsNothing() =
        runTest {
            api.times[SaveSlots.RESUME] = 1_700_000_000
            val starts = starts()
            starts.play(crash)
            runCurrent()
            calls.clear()

            starts.cancel()

            assertEquals(emptyList<String>(), calls)
            assertNull(starts.offer)
        }

    @Test
    fun aGameWithoutASerialStartsAtOnce() =
        runTest {
            val starts = starts()

            starts.play(exe)

            assertEquals(listOf("start padtest null"), calls)
        }

    @Test
    fun aSecondPlayWhileCheckingIsIgnored() =
        runTest {
            val starts = starts()

            starts.play(crash)
            starts.play(crash)
            runCurrent()

            assertEquals(1, calls.count { it.startsWith("start") })
        }

    @Test
    fun aPlayWhileOfferingIsIgnored() =
        runTest {
            api.times[SaveSlots.RESUME] = 1_700_000_000
            val starts = starts()
            starts.play(crash)
            runCurrent()

            starts.play(exe)

            assertEquals(0, calls.count { it.startsWith("start") })
            assertSame(crash, starts.offer!!.game)
        }

    @Test
    fun answersWithoutAnOfferDoNothing() =
        runTest {
            val starts = starts()

            starts.resume()
            starts.startOver()
            starts.cancel()

            assertEquals(emptyList<String>(), calls)
        }
}
