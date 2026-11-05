// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.AchievementsAccount
import io.github.ilyalissoboi.goosestation.core.AchievementsQueue
import io.github.ilyalissoboi.goosestation.settings.AchievementsSettings
import io.github.ilyalissoboi.goosestation.settings.MemoryCoreSettings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        AchievementsPage(backgroundScope, StandardTestDispatcher(testScheduler), api, settings).also {
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
            assertEquals(
                AchievementsSettings(hardcore = true, messages = true, sounds = false, indicators = true),
                page.settings,
            )
        }

    @Test
    fun theNewRowsLoadAndSaveOnlyWhenChanged() =
        runTest {
            settings.setBool("Cheevos", "EncoreMode", true)
            settings.setInt("Cheevos", "NotificationsDuration", 12)
            val page = page()
            val loaded = page.settings!!
            assertEquals(true, loaded.encore)
            assertEquals(false, loaded.spectator)
            assertEquals(false, loaded.unofficial)
            assertEquals(12, loaded.unlockSeconds)
            assertEquals(10, loaded.leaderboardSeconds)
            val before = settings.values.toMap()

            page.change(loaded.copy(spectator = true, leaderboardSeconds = 20))
            runCurrent()

            assertEquals(
                before + mapOf("Cheevos/SpectatorMode" to "true", "Cheevos/LeaderboardsDuration" to "20"),
                settings.values,
            )
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

            page.change(page.settings!!.copy(messages = false))
            runCurrent()

            assertFalse(settings.getBool("Cheevos", "Notifications", true))
            assertFalse(settings.getBool("Cheevos", "LeaderboardNotifications", true))
            assertTrue(calls.contains("applySettings"))
        }

    @Test
    fun onlyChangedKeysAreSaved() =
        runTest {
            val page = page()

            page.change(page.settings!!.copy(hardcore = true))
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
            val page = AchievementsPage(backgroundScope, StandardTestDispatcher(testScheduler), api, settings)

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
