// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.NumberScale

/** The Achievements page's switches and times, by settings.ini's `[Cheevos]` names, each with the core's default. */
object AchievementsSettings {
    private const val CHEEVOS = "Cheevos"

    /** Hardcore mode: from the next game start, or a reset. */
    val HARDCORE = BoolSetting(CHEEVOS, "ChallengeMode", false)

    /** Unlock messages; with [LEADERBOARD_NOTIFICATIONS], the page's one "messages" row ([messages]). */
    val NOTIFICATIONS = BoolSetting(CHEEVOS, "Notifications", true)
    val LEADERBOARD_NOTIFICATIONS = BoolSetting(CHEEVOS, "LeaderboardNotifications", true)
    val SOUNDS = BoolSetting(CHEEVOS, "SoundEffects", true)

    /** Leaderboard trackers, challenge badges and the progress pop-up. */
    val OVERLAYS = BoolSetting(CHEEVOS, "Overlays", true)

    /** Each session as if nothing were unlocked. */
    val ENCORE = BoolSetting(CHEEVOS, "EncoreMode", false)

    /** Unlocks aren't sent. */
    val SPECTATOR = BoolSetting(CHEEVOS, "SpectatorMode", false)

    /** Unofficial sets listed too. */
    val UNOFFICIAL = BoolSetting(CHEEVOS, "UnofficialTestMode", false)

    /** How long an unlock's card shows, in seconds. */
    val UNLOCK_SECONDS = IntSetting(CHEEVOS, "NotificationsDuration", 5)

    /** How long a leaderboard message shows, in seconds. */
    val LEADERBOARD_SECONDS = IntSetting(CHEEVOS, "LeaderboardsDuration", 10)

    val all: List<Setting<*>> =
        listOf(
            HARDCORE,
            NOTIFICATIONS,
            LEADERBOARD_NOTIFICATIONS,
            SOUNDS,
            OVERLAYS,
            ENCORE,
            SPECTATOR,
            UNOFFICIAL,
            UNLOCK_SECONDS,
            LEADERBOARD_SECONDS,
        )

    /** The messages' durations, as desktop offers them, in seconds. */
    val DURATIONS = NumberScale.Range(3, 30, 1, presets = listOf(3, 5, 10, 15, 30)) { "$it s" }

    /** Unlock and leaderboard messages, as the page's one row has them: on while either is. */
    fun messages(values: SettingValues): Boolean = values[NOTIFICATIONS] || values[LEADERBOARD_NOTIFICATIONS]

    /** [values] with both kinds of messages [on] or off. */
    fun withMessages(
        values: SettingValues,
        on: Boolean,
    ): SettingValues = values.with(NOTIFICATIONS, on).with(LEADERBOARD_NOTIFICATIONS, on)
}
