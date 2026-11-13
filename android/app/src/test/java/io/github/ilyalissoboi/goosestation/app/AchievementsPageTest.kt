// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.AchievementsAccount
import io.github.ilyalissoboi.goosestation.core.AchievementsQueue
import io.github.ilyalissoboi.goosestation.settings.AchievementsSettings
import io.github.ilyalissoboi.goosestation.settings.MemoryCoreSettings
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Achievements page: its account, sign-in and switches, with a fake core and settings in memory. */
class AchievementsPageTest {
    private val calls = mutableListOf<String>()
    private val api = FakeCoreApi(calls)
    private val settings = MemoryCoreSettings()

    private fun TestScope.page() =
        AchievementsPage(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            StandardTestDispatcher(testScheduler),
            api,
            settings,
        ).also {
            it.load()
            runCurrent()
            calls.clear()
        }

    @Test
    fun loadsAccountAndSwitches() =
        runTest {
            settings.setBool("Cheevos", "ChallengeMode", true)
            settings.setBool("Cheevos", "SoundEffects", false)
            val page = page()

            assertEquals(AchievementsAccount.SIGNED_OUT, page.account!!.state)
            val loaded = page.settings!!
            assertEquals(true, loaded[AchievementsSettings.HARDCORE])
            assertEquals(true, AchievementsSettings.messages(loaded))
            assertEquals(false, loaded[AchievementsSettings.SOUNDS])
            assertEquals(true, loaded[AchievementsSettings.OVERLAYS])
        }

    @Test
    fun theNewRowsLoadAndSaveOnlyWhenChanged() =
        runTest {
            settings.setBool("Cheevos", "EncoreMode", true)
            settings.setInt("Cheevos", "NotificationsDuration", 12)
            val page = page()
            val loaded = page.settings!!
            assertEquals(true, loaded[AchievementsSettings.ENCORE])
            assertEquals(false, loaded[AchievementsSettings.SPECTATOR])
            assertEquals(false, loaded[AchievementsSettings.UNOFFICIAL])
            assertEquals(12, loaded[AchievementsSettings.UNLOCK_SECONDS])
            assertEquals(10, loaded[AchievementsSettings.LEADERBOARD_SECONDS])
            val before = settings.values.toMap()

            page.change(
                loaded.with(AchievementsSettings.SPECTATOR, true).with(AchievementsSettings.LEADERBOARD_SECONDS, 20),
            )
            runCurrent()

            assertEquals(
                before + mapOf("Cheevos/SpectatorMode" to "true", "Cheevos/LeaderboardsDuration" to "20"),
                settings.values,
            )
        }

    // A sign-in waits for the server on a queue of its own: the page's reads and writes on the core queue don't wait
    // behind it. Real threads, as the sign-in blocks its thread.
    @Test
    fun aSignInDoesntHoldUpASettingsRead() =
        runBlocking {
            val gate = java.util.concurrent.CountDownLatch(1)
            api.signInGate = gate
            val core =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
                    .asCoroutineDispatcher()
            val signIn =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
                    .asCoroutineDispatcher()
            try {
                val page = AchievementsPage(this, core, signIn, api, settings)
                page.signIn("goose", "not-a-real-password")
                assertTrue(page.signingIn)

                page.load()
                withTimeout(5_000) { while (page.settings == null) delay(10) }
                assertTrue("still signing in while the read landed", page.signingIn)

                gate.countDown()
                withTimeout(5_000) { while (page.signingIn) delay(10) }
            } finally {
                gate.countDown()
                core.close()
                signIn.close()
            }
        }

    @Test
    fun signInSuccessReadsTheAccount() =
        runTest {
            val page = page()

            page.signIn("goose", "not-a-real-password")
            assertTrue(page.signingIn)
            runCurrent()

            assertFalse(page.signingIn)
            assertEquals(listOf("signIn goose", "getAchievementsAccount"), calls)
            assertEquals(AchievementsAccount.SIGNED_IN, page.account!!.state)
            assertNull(page.error)
        }

    @Test
    fun signInFailureKeepsTheErrorAndClearsThePassword() =
        runTest {
            val page = page()
            api.signInError = "Invalid username/password."
            val cleared = page.passwordCleared

            page.signIn("goose", "wrong")
            runCurrent()

            assertEquals("Invalid username/password.", page.error)
            assertEquals(cleared + 1, page.passwordCleared)
            assertEquals(AchievementsAccount.SIGNED_OUT, page.account!!.state)
        }

    @Test
    fun blankFieldsDoNothing() =
        runTest {
            val page = page()

            page.signIn("", "not-a-real-password")
            page.signIn("goose", "")
            runCurrent()

            assertEquals(emptyList<String>(), calls)
            assertFalse(page.signingIn)
        }

    @Test
    fun signOutReadsTheAccount() =
        runTest {
            val page = page()
            page.signIn("goose", "not-a-real-password")
            runCurrent()
            calls.clear()

            page.signOut()
            runCurrent()

            assertEquals(listOf("signOut", "getAchievementsAccount"), calls)
            assertEquals(AchievementsAccount.SIGNED_OUT, page.account!!.state)
        }

    @Test
    fun messagesSwitchSetsBothKeys() =
        runTest {
            val page = page()

            page.change(AchievementsSettings.withMessages(page.settings!!, false))
            runCurrent()

            assertFalse(settings.getBool("Cheevos", "Notifications", true))
            assertFalse(settings.getBool("Cheevos", "LeaderboardNotifications", true))
            assertTrue(calls.contains("applySettings"))
        }

    @Test
    fun onlyChangedKeysAreSaved() =
        runTest {
            val page = page()

            page.change(page.settings!!.with(AchievementsSettings.HARDCORE, true))
            runCurrent()

            assertEquals(mapOf("Cheevos/ChallengeMode" to "true"), settings.values)
        }

    @Test
    fun expiredShowsUntilASignIn() =
        runTest {
            val page = page()

            page.loginExpired()
            runCurrent()
            assertTrue(page.expired)

            page.signIn("goose", "not-a-real-password")
            runCurrent()
            assertFalse(page.expired)
        }

    // The core's sign-in finished (or failed) while the page was open: it shows the account as it is now.
    @Test
    fun refreshReadsTheAccountAgain() =
        runTest {
            val page = page()
            api.account = AchievementsAccount(AchievementsAccount.SIGNED_IN, "goose", "Goose", 300, 940)

            page.refresh()
            runCurrent()

            assertEquals(AchievementsAccount.SIGNED_IN, page.account!!.state)
        }

    @Test
    fun refreshDoesNothingUntilLoaded() =
        runTest {
            val page =
                AchievementsPage(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    StandardTestDispatcher(testScheduler),
                    api,
                    settings,
                )

            page.refresh()
            runCurrent()

            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun readsTheQueueWithTheAccount() =
        runTest {
            api.queue = AchievementsQueue(2, arrayOf("swan"), intArrayOf(1))
            val page = page()

            assertEquals(2, page.queue.own)
            assertEquals(listOf("swan" to 1), page.queue.others())
        }
}
