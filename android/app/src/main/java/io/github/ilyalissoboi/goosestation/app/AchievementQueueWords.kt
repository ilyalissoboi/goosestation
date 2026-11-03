// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/** The words for unlocks and leaderboard entries waiting to be sent (both counted as unlocks), word for word. */
object AchievementQueueWords {
    /** Under an unlock card's text, for one earned without a connection. */
    const val CARD_NOTE = "Sent when you're online"

    /** Once, when a game starts from its saved set without a connection. */
    const val OFFLINE_START = "Offline: achievements are saved and sent later (casual mode)"

    /** The menu's Leaderboards tab, in a game started offline. */
    const val LEADERBOARDS_OFFLINE = "Not available offline"

    private const val EXPIRED = "RetroAchievements sign-in expired: sign in again in Settings"

    private fun unlocks(count: Int) = if (count == 1) "1 unlock" else "$count unlocks"

    /** After the menu row's count: " · 2 to send", or nothing with none waiting. */
    fun rowSuffix(count: Int) = if (count > 0) " · $count to send" else ""

    /** Settings → Achievements, for the account's own records. */
    fun ownWaiting(count: Int) = "${unlocks(count)} waiting to be sent"

    /** Settings → Achievements, for another account's records. */
    fun othersKept(
        user: String,
        count: Int,
    ) = "${unlocks(count)} for $user ${if (count == 1) "is" else "are"} kept until $user signs in again"

    /** The sign-in expiry message, with what it keeps. */
    fun expiry(count: Int) =
        if (count > 0) {
            "$EXPIRED. ${unlocks(count)} ${if (count == 1) "is" else "are"} kept and sent once you sign in again"
        } else {
            EXPIRED
        }

    fun sent(count: Int) = "${unlocks(count)} sent to RetroAchievements"

    fun refused(title: String) = "Couldn't send '$title': RetroAchievements refused it"
}
