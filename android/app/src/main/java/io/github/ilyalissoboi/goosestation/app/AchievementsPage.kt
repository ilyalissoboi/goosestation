// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.AchievementsAccount
import io.github.ilyalissoboi.goosestation.settings.AchievementsSettings
import io.github.ilyalissoboi.goosestation.settings.CoreSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Achievements page: the RetroAchievements account, signing in and out, and the page's switches, read and saved
 * through [api] and [coreSettings] on [core], one call at a time. Main thread only. Each change applies from the next game
 * start: the page can't be opened during a game.
 */
class AchievementsPage(
    private val scope: CoroutineScope,
    private val core: CoroutineDispatcher,
    private val api: CoreApi,
    private val coreSettings: CoreSettings,
) {
    /** The account, as last read; null until it is. */
    var account by mutableStateOf<AchievementsAccount?>(null)
        private set

    /** The switches, as last read or changed; null until read. */
    var settings by mutableStateOf<AchievementsSettings?>(null)
        private set

    /** A sign-in is waiting for the server. */
    var signingIn by mutableStateOf(false)
        private set

    /** Why the last sign-in failed, until the next one. */
    var error by mutableStateOf<String?>(null)
        private set

    /** The server rejected the stored token, so the player must sign in again. */
    var expired by mutableStateOf(false)
        private set

    /** Counts failed sign-ins: the page clears its password field each time it changes. */
    var passwordCleared by mutableIntStateOf(0)
        private set

    /** Reads the account and switches, from none shown. */
    fun load() {
        account = null
        settings = null
        scope.launch {
            val (read, switches) =
                withContext(core) { api.getAchievementsAccount() to AchievementsSettings.load(coreSettings) }
            account = read
            settings = switches
        }
    }

    /** Signs in as [username] with [password]; blank fields, or a sign-in already waiting, do nothing. */
    fun signIn(
        username: String,
        password: String,
    ) {
        if (signingIn || username.isBlank() || password.isEmpty()) return
        signingIn = true
        error = null
        scope.launch {
            val (failure, read) =
                withContext(core) {
                    val failure = api.signIn(username.trim(), password)
                    failure to api.getAchievementsAccount()
                }
            signingIn = false
            account = read
            if (failure == null) {
                expired = false
            } else {
                error = failure
                passwordCleared++
            }
        }
    }

    fun signOut() {
        error = null
        scope.launch {
            account =
                withContext(core) {
                    api.signOut()
                    api.getAchievementsAccount()
                }
        }
    }

    /** Saves the switches' change to [new], only the fields that changed, then has the core apply it. */
    fun change(new: AchievementsSettings) {
        val old = settings ?: return
        if (new == old) return
        settings = new
        scope.launch(core) {
            new.save(coreSettings, old)
            api.applySettings()
        }
    }

    /** The server rejected the stored token: the page says so until a sign-in works. */
    fun loginExpired() {
        expired = true
        scope.launch { account = withContext(core) { api.getAchievementsAccount() } }
    }
}
