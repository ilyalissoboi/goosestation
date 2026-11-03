// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

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
    }

    companion object {
        private const val CHEEVOS = "Cheevos"
        private const val HARDCORE = "ChallengeMode"
        private const val NOTIFICATIONS = "Notifications"
        private const val LEADERBOARD_NOTIFICATIONS = "LeaderboardNotifications"
        private const val SOUNDS = "SoundEffects"
        private const val OVERLAYS = "Overlays"

        /** The switches in [s], each the core's default when it isn't set. */
        fun load(s: CoreSettings) =
            AchievementsSettings(
                hardcore = s.getBool(CHEEVOS, HARDCORE, false),
                messages =
                    s.getBool(CHEEVOS, NOTIFICATIONS, true) || s.getBool(CHEEVOS, LEADERBOARD_NOTIFICATIONS, true),
                sounds = s.getBool(CHEEVOS, SOUNDS, true),
                indicators = s.getBool(CHEEVOS, OVERLAYS, true),
            )
    }
}
