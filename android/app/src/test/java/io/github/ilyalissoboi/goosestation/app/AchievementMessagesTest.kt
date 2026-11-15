// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameAchievements
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** RetroAchievements' messages over the game ([AchievementMessages]): what is said, and that each is said once. */
class AchievementMessagesTest {
    private val said = mutableListOf<String>()
    private var running = true
    private var queued = 0
    private var status = GameAchievements.READY
    private var offline = false

    /** The game stops while its achievements are read. */
    private var stopsWhileRead = false

    private val reader =
        object : AchievementsReader {
            override fun queuedOwn() = queued

            override fun gameAchievements(): GameAchievements {
                if (stopsWhileRead) running = false
                return GameAchievements(status, 0, 10, 0, 100, true, false, false, offline)
            }
        }

    private fun TestScope.messages(): AchievementMessages {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return AchievementMessages(backgroundScope, dispatcher, dispatcher, reader, { running }) { said += it }
    }

    // --- The sign-in's expiry ---

    @Test
    fun theExpiryIsSaidOnceAtTheNextGamesStartWithWhatTheQueueKeeps() =
        runTest {
            val messages = messages()
            queued = 2

            messages.loginExpired()
            runCurrent()
            assertEquals(emptyList<String>(), said)

            messages.gameStarted()
            runCurrent()
            messages.gameStarted()
            runCurrent()

            assertEquals(listOf(AchievementQueueWords.expiry(2)), said)
        }

    @Test
    fun noExpiryIsSaidWithoutOne() =
        runTest {
            val messages = messages()

            messages.gameStarted()
            runCurrent()

            assertEquals(emptyList<String>(), said)
        }

    // --- Couldn't reach, or offline ---

    @Test
    fun couldNotReachIsSaidOncePerGame() =
        runTest {
            val messages = messages()
            status = GameAchievements.UNREACHABLE
            messages.gameStarted()

            messages.achievementsChanged()
            runCurrent()
            messages.achievementsChanged()
            runCurrent()

            assertEquals(listOf(AchievementMessages.UNREACHABLE), said)
        }

    @Test
    fun aGameStartedOfflineSaysSoOnce() =
        runTest {
            val messages = messages()
            offline = true
            messages.gameStarted()

            messages.achievementsChanged()
            runCurrent()
            messages.achievementsChanged()
            runCurrent()

            assertEquals(listOf(AchievementQueueWords.OFFLINE_START), said)
        }

    @Test
    fun nothingIsSaidWhenReadyAndOnlineOrStillLoading() =
        runTest {
            val messages = messages()
            messages.gameStarted()

            messages.achievementsChanged()
            runCurrent()
            status = GameAchievements.LOADING
            offline = true
            messages.achievementsChanged()
            runCurrent()

            assertEquals(emptyList<String>(), said)
        }

    @Test
    fun nothingIsSaidWithoutAGameRunning() =
        runTest {
            val messages = messages()
            status = GameAchievements.UNREACHABLE
            running = false

            messages.achievementsChanged()
            runCurrent()

            assertEquals(emptyList<String>(), said)
        }

    @Test
    fun nothingIsSaidWhenTheGameStopsWhileItsStatusIsRead() =
        runTest {
            val messages = messages()
            status = GameAchievements.UNREACHABLE
            messages.gameStarted()
            stopsWhileRead = true

            messages.achievementsChanged()
            runCurrent()

            assertEquals(emptyList<String>(), said)
        }

    @Test
    fun aNewGameMaySayItAgain() =
        runTest {
            val messages = messages()
            status = GameAchievements.UNREACHABLE
            messages.gameStarted()
            messages.achievementsChanged()
            runCurrent()

            messages.gameStarted()
            messages.achievementsChanged()
            runCurrent()

            assertEquals(List(2) { AchievementMessages.UNREACHABLE }, said)
        }
}
