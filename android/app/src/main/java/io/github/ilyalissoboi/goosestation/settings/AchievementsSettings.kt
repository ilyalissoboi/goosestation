// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.NumberScale

/** The Achievements page's switches, by settings.ini's `[Cheevos]` names. */
data class AchievementsSettings(
    /** `ChallengeMode`: hardcore mode, from the next game start. */
    val hardcore: Boolean,
    /** `Notifications` and `LeaderboardNotifications` together: unlock and leaderboard messages over the game. */
    val messages: Boolean,
    /** `SoundEffects`. */
    val sounds: Boolean,
    /** `Overlays`: leaderboard trackers, challenge badges and the progress pop-up. */
    val indicators: Boolean,
    /** `EncoreMode`: each session as if nothing were unlocked. */
    val encore: Boolean = false,
    /** `SpectatorMode`: unlocks aren't sent. */
    val spectator: Boolean = false,
    /** `UnofficialTestMode`: unofficial sets listed too. */
    val unofficial: Boolean = false,
    /** `NotificationsDuration`: how long an unlock's card shows, in seconds. */
    val unlockSeconds: Int = 5,
    /** `LeaderboardsDuration`: how long a leaderboard message shows, in seconds. */
    val leaderboardSeconds: Int = 10,
) {
    /** Writes to [s] the fields that differ from [old], and nothing else. */
    fun save(
        s: CoreSettings,
        old: AchievementsSettings,
    ) {
        if (hardcore != old.hardcore) s.setBool(CHEEVOS, HARDCORE, hardcore)
        if (messages != old.messages) {
            s.setBool(CHEEVOS, NOTIFICATIONS, messages)
            s.setBool(CHEEVOS, LEADERBOARD_NOTIFICATIONS, messages)
        }
        if (sounds != old.sounds) s.setBool(CHEEVOS, SOUNDS, sounds)
        if (indicators != old.indicators) s.setBool(CHEEVOS, OVERLAYS, indicators)
        if (encore != old.encore) s.setBool(CHEEVOS, ENCORE, encore)
        if (spectator != old.spectator) s.setBool(CHEEVOS, SPECTATOR, spectator)
        if (unofficial != old.unofficial) s.setBool(CHEEVOS, UNOFFICIAL, unofficial)
        if (unlockSeconds != old.unlockSeconds) s.setInt(CHEEVOS, UNLOCK_SECONDS, unlockSeconds)
        if (leaderboardSeconds != old.leaderboardSeconds) s.setInt(CHEEVOS, LEADERBOARD_SECONDS, leaderboardSeconds)
    }

    companion object {
        private const val CHEEVOS = "Cheevos"
        private const val HARDCORE = "ChallengeMode"
        private const val NOTIFICATIONS = "Notifications"
        private const val LEADERBOARD_NOTIFICATIONS = "LeaderboardNotifications"
        private const val SOUNDS = "SoundEffects"
        private const val OVERLAYS = "Overlays"
        private const val ENCORE = "EncoreMode"
        private const val SPECTATOR = "SpectatorMode"
        private const val UNOFFICIAL = "UnofficialTestMode"
        private const val UNLOCK_SECONDS = "NotificationsDuration"
        private const val LEADERBOARD_SECONDS = "LeaderboardsDuration"

        /** The messages' durations, as desktop offers them, in seconds. */
        val DURATIONS = NumberScale.Range(3, 30, 1, presets = listOf(3, 5, 10, 15, 30)) { "$it s" }

        /** The switches in [s], each the core's default when it isn't set. */
        fun load(s: CoreSettings) =
            AchievementsSettings(
                hardcore = s.getBool(CHEEVOS, HARDCORE, false),
                messages =
                    s.getBool(CHEEVOS, NOTIFICATIONS, true) || s.getBool(CHEEVOS, LEADERBOARD_NOTIFICATIONS, true),
                sounds = s.getBool(CHEEVOS, SOUNDS, true),
                indicators = s.getBool(CHEEVOS, OVERLAYS, true),
                encore = s.getBool(CHEEVOS, ENCORE, false),
                spectator = s.getBool(CHEEVOS, SPECTATOR, false),
                unofficial = s.getBool(CHEEVOS, UNOFFICIAL, false),
                unlockSeconds = s.getInt(CHEEVOS, UNLOCK_SECONDS, 5),
                leaderboardSeconds = s.getInt(CHEEVOS, LEADERBOARD_SECONDS, 10),
            )
    }
}
