// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.core.AchievementsAccount
import io.github.ilyalissoboi.goosestation.core.AchievementsQueue
import io.github.ilyalissoboi.goosestation.settings.AchievementsSettings
import io.github.ilyalissoboi.goosestation.settings.NextStart
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.GameContext
import io.github.ilyalissoboi.goosestation.ui.components.LocalGameContext
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Achievements page, driven with keys: signing in, the signed-in state, and the switches. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AchievementsScreenTest {
    private companion object {
        val SIGNED_OUT = AchievementsAccount(AchievementsAccount.SIGNED_OUT, "", "", 0, 0)
        val SIGNED_IN = AchievementsAccount(AchievementsAccount.SIGNED_IN, "goose", "Goose", 300, 940)
        val NOT_CONNECTED = AchievementsAccount(AchievementsAccount.NOT_CONNECTED, "goose", "", 0, 0)
        val SWITCHES = pageValues(AchievementsSettings.all)
    }

    @get:Rule val rule = createComposeRule()

    private val signIns = mutableListOf<Pair<String, String>>()
    private var signOuts = 0
    private val changes = mutableListOf<SettingValues>()
    private var backs = 0

    private fun show(
        account: AchievementsAccount,
        queue: AchievementsQueue = AchievementsQueue.NONE,
    ) {
        rule.setContent {
            var settings by remember { mutableStateOf(SWITCHES) }
            GooseStationTheme {
                AchievementsScreen(
                    account = account,
                    settings = settings,
                    signingIn = false,
                    error = null,
                    expired = false,
                    passwordCleared = 0,
                    queue = queue,
                    onSignIn = { user, password -> signIns += user to password },
                    onSignOut = { signOuts++ },
                    onChange = {
                        changes += it
                        settings = it
                    },
                    onBack = { backs++ },
                )
            }
        }
        rule.waitForIdle()
    }

    // A text field, by its label (its content description, as TalkBack reads it).
    private fun field(label: String) = hasSetTextAction().and(hasContentDescription(label))

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun pressUntilFocused(
        key: Key,
        text: String,
    ) {
        repeat(12) {
            if (rule
                    .onAllNodes(
                        isFocused().and(hasText(text, substring = true)),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                return
            }
            press(key)
        }
        rule.onNode(isFocused()).assert(hasText(text, substring = true))
    }

    @Test
    fun signedOutFocusesUsername() {
        show(SIGNED_OUT)

        rule.onNode(isFocused()).assert(field("Username"))
    }

    @Test
    fun typingAndSigningIn() {
        show(SIGNED_OUT)

        rule.onNode(field("Username")).performTextInput("goose")
        rule.onNode(field("Password")).performTextInput("not-a-real-password")
        pressUntilFocused(Key.DirectionDown, "Sign in")
        press(Key.ButtonA)

        assertEquals(listOf("goose" to "not-a-real-password"), signIns)
    }

    @Test
    fun passwordIsMasked() {
        show(SIGNED_OUT)

        rule.onNode(field("Password")).performTextInput("not-a-real-password")

        // A secure field: drawn as dots, and marked as a password, which Android's accessibility masks.
        assertTrue(SemanticsProperties.Password in rule.onNode(field("Password")).fetchSemanticsNode().config)
    }

    @Test
    fun signedInFocusesSignOut() {
        show(SIGNED_IN)

        rule.onNodeWithText("Signed in as Goose").assertExists()
        rule.onNode(isFocused()).assert(hasText("Sign out"))
        press(Key.ButtonA)
        assertEquals(1, signOuts)
    }

    @Test
    fun notConnectedShowsTheNote() {
        show(NOT_CONNECTED)

        rule.onNodeWithText("Signed in as goose").assertExists()
        rule.onNodeWithText("Not connected. RetroAchievements is checked again when a game starts.").assertExists()
    }

    // Still checking the stored sign-in (the app's first minute, a slow network): no points to show yet.
    @Test
    fun connectingShowsTheNote() {
        show(AchievementsAccount(AchievementsAccount.CONNECTING, "goose", "", 0, 0))

        rule.onNodeWithText("Signed in as goose").assertExists()
        rule.onNodeWithText("Not connected. RetroAchievements is checked again when a game starts.").assertExists()
        rule.onNodeWithText("0 points").assertDoesNotExist()
    }

    @Test
    fun switchesToggleWithA() {
        show(SIGNED_IN)

        pressUntilFocused(Key.DirectionDown, "Hardcore mode")
        press(Key.ButtonA)
        rule.onNode(isFocused()).assertIsOn()
        pressUntilFocused(Key.DirectionDown, "Unlock and leaderboard messages")
        press(Key.ButtonA)

        assertEquals(
            listOf(
                SWITCHES.with(AchievementsSettings.HARDCORE, true),
                AchievementsSettings.withMessages(SWITCHES.with(AchievementsSettings.HARDCORE, true), false),
            ),
            changes,
        )
    }

    @Test
    fun bGoesBack() {
        show(SIGNED_IN)

        press(Key.ButtonB)

        assertEquals(1, backs)
        assertFalse(signOuts > 0)
    }

    @Test
    fun ownRecordsAreCounted() {
        show(NOT_CONNECTED, AchievementsQueue(2, emptyArray(), IntArray(0)))

        rule.onNodeWithText("2 unlocks waiting to be sent").assertExists()
    }

    // Review Focus 3: another account's records stay, and the page says whose they are.
    @Test
    fun otherUsersRecordsAreShown() {
        show(SIGNED_OUT, AchievementsQueue(0, arrayOf("swan"), intArrayOf(1)))

        rule.onNodeWithText("1 unlock for swan is kept until swan signs in again").assertExists()
        rule.onNodeWithText("waiting to be sent", substring = true).assertDoesNotExist()
    }

    // Final review: an expired sign-in keeps the username, and its records are shown as kept for it.
    @Test
    fun anExpiredAccountsRecordsAreShown() {
        show(
            AchievementsAccount(AchievementsAccount.SIGNED_OUT, "goose", "", 0, 0),
            AchievementsQueue(2, emptyArray(), IntArray(0)),
        )

        rule.onNodeWithText("2 unlocks for goose are kept until goose signs in again").assertExists()
    }

    @Test
    fun theNewRowsFollowLiveIndicators() {
        show(SIGNED_IN)

        pressUntilFocused(Key.DirectionDown, "Live indicators")
        for (row in listOf(
            "Encore mode",
            "Spectator mode",
            "Test unofficial achievements",
            "Unlock message time",
            "Leaderboard message time",
        )) {
            press(Key.DirectionDown)
            rule.onNode(isFocused()).assert(hasText(row, substring = true))
        }
    }

    @Test
    fun theTimesStepBySeconds() {
        show(SIGNED_IN)

        pressUntilFocused(Key.DirectionDown, "Unlock message time")
        rule.onNode(isFocused()).assert(hasText("5 s", substring = true))
        press(Key.DirectionRight)

        assertEquals(SWITCHES.with(AchievementsSettings.UNLOCK_SECONDS, 6), changes.last())
    }

    @Test
    fun theTimesGoWithTheMessages() {
        show(SIGNED_IN)

        pressUntilFocused(Key.DirectionDown, "Unlock and leaderboard messages")
        press(Key.ButtonA)

        rule.onNodeWithText("Unlock message time").assertDoesNotExist()
        rule.onNodeWithText("Leaderboard message time").assertDoesNotExist()
    }

    // A game's rows, in its running game: hardcore says when it applies, and while hardcore is on, the three that
    // would end it wait for the next start.
    private fun showGameRows(hardcoreActive: Boolean) {
        rule.setContent {
            GooseStationTheme {
                var values by remember { mutableStateOf(gamePageValues(AchievementsSettings.all)) }
                CompositionLocalProvider(LocalGameContext provides GameContext("Pad Test", true, hardcoreActive)) {
                    SettingsPage("Achievements", onBack = {}) { AchievementRows(values, onChange = { values = it }) }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun inAHardcoreGameEncoreIsDisabled() {
        showGameRows(hardcoreActive = true)

        for (row in listOf("Encore mode", "Spectator mode", "Test unofficial achievements")) {
            rule
                .onNode(
                    hasText(row, substring = true) and hasText(HARDCORE_WAIT_NOTE, substring = true),
                ).assertIsNotEnabled()
        }
    }

    @Test
    fun aRunningGamesHardcoreSaysWhenItApplies() {
        showGameRows(hardcoreActive = false)

        rule
            .onNode(
                hasText("Hardcore mode", substring = true) and hasText(NextStart.HARDCORE_NOTE, substring = true),
            ).assertExists()
        rule
            .onNode(
                hasText("Encore mode", substring = true) and hasText("Use global", substring = true),
            ).assertIsEnabled()
    }
}
