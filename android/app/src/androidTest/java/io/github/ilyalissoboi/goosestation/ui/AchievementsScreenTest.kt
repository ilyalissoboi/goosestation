// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
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
import io.github.ilyalissoboi.goosestation.settings.AchievementsSettings
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
        val SWITCHES = AchievementsSettings(hardcore = false, messages = true, sounds = true, indicators = true)
    }

    @get:Rule val rule = createComposeRule()

    private val signIns = mutableListOf<Pair<String, String>>()
    private var signOuts = 0
    private val changes = mutableListOf<AchievementsSettings>()
    private var backs = 0

    private fun show(account: AchievementsAccount) {
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

    @Test
    fun switchesToggleWithA() {
        show(SIGNED_IN)

        pressUntilFocused(Key.DirectionDown, "Hardcore mode")
        press(Key.ButtonA)
        rule.onNode(isFocused()).assertIsOn()
        pressUntilFocused(Key.DirectionDown, "Unlock and leaderboard messages")
        press(Key.ButtonA)

        assertEquals(
            listOf(SWITCHES.copy(hardcore = true), SWITCHES.copy(hardcore = true, messages = false)),
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
}
