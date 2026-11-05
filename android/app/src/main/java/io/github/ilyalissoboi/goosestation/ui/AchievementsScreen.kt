// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.AchievementQueueWords
import io.github.ilyalissoboi.goosestation.core.AchievementsAccount
import io.github.ilyalissoboi.goosestation.core.AchievementsQueue
import io.github.ilyalissoboi.goosestation.settings.AchievementsSettings
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.NumberRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.components.TextFieldRow
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

/** Hardcore mode's note, word for word from the design. */
const val HARDCORE_NOTE =
    "No save state loading, patch codes or fast forward. Starts the next time a game starts or resets. " +
        "RetroAchievements may not count hardcore unlocks from GooseStation until it's registered."

const val NOT_CONNECTED_NOTE = "Not connected. RetroAchievements is checked again when a game starts."

const val EXPIRED_NOTE = "Your RetroAchievements sign-in has expired. Sign in again."

/**
 * The Achievements page. Signed out ([account]'s state), the username and password fields and **Sign in**
 * ([onSignIn]), with [error] or the [expired] notice under them; [passwordCleared] changing clears the password.
 * Otherwise, who is signed in and **Sign out** ([onSignOut]). Then what's waiting to be sent ([queue]): the account's
 * own count, and each other account's, kept for it. Then the [settings]: switches, and the messages' times while
 * they show, each change as the whole settings with that field changed ([onChange]). The username, or Sign out, is
 * focused on opening; B or Back go back ([onBack]).
 */
@Composable
fun AchievementsScreen(
    account: AchievementsAccount,
    settings: AchievementsSettings,
    signingIn: Boolean,
    error: String?,
    expired: Boolean,
    passwordCleared: Int,
    queue: AchievementsQueue = AchievementsQueue.NONE,
    onSignIn: (username: String, password: String) -> Unit,
    onSignOut: () -> Unit,
    onChange: (AchievementsSettings) -> Unit,
    onBack: () -> Unit,
) {
    val colors = GooseStationTheme.colors
    val signedOut = account.state == AchievementsAccount.SIGNED_OUT
    val first = remember { FocusRequester() }
    LaunchedEffect(signedOut) { first.requestFocus() }

    val username = rememberTextFieldState(account.username)
    // Never saved across a recreation: the password stays in this composition only.
    val password = remember { TextFieldState() }
    LaunchedEffect(passwordCleared) { if (passwordCleared > 0) password.clearText() }
    val signIn = { onSignIn(username.text.toString(), password.text.toString()) }

    SettingsPage("Achievements", onBack = onBack) {
        if (signedOut) {
            TextFieldRow("Username", username, Modifier.focusRequester(first))
            TextFieldRow(
                "Password",
                password,
                password = true,
                imeAction = ImeAction.Done,
                onDone = signIn,
            )
            // An expired sign-in keeps the username: what it had waiting is kept for it.
            if (queue.own > 0 && account.username.isNotEmpty()) {
                Text(
                    AchievementQueueWords.othersKept(account.username, queue.own),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            val notice = error ?: if (expired) EXPIRED_NOTE else null
            notice?.let {
                Notice(NoticeStatus.Danger, Modifier.padding(horizontal = 16.dp)) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
                }
            }
            ActionButton(
                if (signingIn) "Signing in…" else "Sign in",
                onClick = signIn,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                enabled = !signingIn,
                kind = ButtonKind.Primary,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Signed in as ${account.displayName.ifEmpty { account.username }}",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                )
                // Not signed in yet, whether still checking or with no connection: no points to show.
                if (account.state == AchievementsAccount.NOT_CONNECTED ||
                    account.state == AchievementsAccount.CONNECTING
                ) {
                    Text(NOT_CONNECTED_NOTE, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                } else {
                    Text(points(account), style = GooseStationTheme.type.mono, color = colors.inkMuted)
                }
                if (queue.own > 0) {
                    Text(
                        AchievementQueueWords.ownWaiting(queue.own),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink,
                    )
                }
            }
            ActionButton(
                "Sign out",
                onClick = onSignOut,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(first),
                kind = ButtonKind.Ghost,
            )
        }
        // Another account's records are sent only once it signs in again.
        for ((user, count) in queue.others()) {
            Text(
                AchievementQueueWords.othersKept(user, count),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        SwitchRow(
            "Hardcore mode",
            settings.hardcore,
            onToggle = { onChange(settings.copy(hardcore = it)) },
            note = HARDCORE_NOTE,
        )
        SwitchRow(
            "Unlock and leaderboard messages",
            settings.messages,
            onToggle = { onChange(settings.copy(messages = it)) },
        )
        SwitchRow("Sounds", settings.sounds, onToggle = { onChange(settings.copy(sounds = it)) })
        SwitchRow(
            "Live indicators",
            settings.indicators,
            onToggle = { onChange(settings.copy(indicators = it)) },
        )
        SwitchRow(
            "Encore mode",
            settings.encore,
            onToggle = { onChange(settings.copy(encore = it)) },
            note = "Each session starts as if nothing were unlocked.",
        )
        SwitchRow(
            "Spectator mode",
            settings.spectator,
            onToggle = { onChange(settings.copy(spectator = it)) },
            note = "Unlocks aren't sent to RetroAchievements.",
        )
        SwitchRow(
            "Test unofficial achievements",
            settings.unofficial,
            onToggle = { onChange(settings.copy(unofficial = it)) },
            note = "Lists unofficial sets, which aren't tracked.",
        )
        if (settings.messages) {
            NumberRow(
                "Unlock message time",
                settings.unlockSeconds,
                AchievementsSettings.DURATIONS,
                onChange = { onChange(settings.copy(unlockSeconds = it)) },
            )
            NumberRow(
                "Leaderboard message time",
                settings.leaderboardSeconds,
                AchievementsSettings.DURATIONS,
                onChange = { onChange(settings.copy(leaderboardSeconds = it)) },
            )
        }
    }
}

/** "1,240 points (hardcore 300)": the softcore and hardcore points, or the one that isn't 0. */
private fun points(account: AchievementsAccount): String {
    val softcore = "%,d".format(account.softcoreScore)
    val hardcore = "%,d".format(account.score)
    return when {
        account.softcoreScore > 0 && account.score > 0 -> "$softcore points (hardcore $hardcore)"
        account.score > 0 -> "$hardcore points"
        else -> "$softcore points"
    }
}
