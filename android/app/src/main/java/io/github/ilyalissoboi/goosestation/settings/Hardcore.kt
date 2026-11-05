// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

/**
 * Hardcore mode as the pages read it: on with `[Cheevos] ChallengeMode` while signed in (`Enabled`). The core then
 * refuses speeds below 100%, rewind, and overclocking below 100% (`Settings::FixIncompatibleSettings`).
 */
object Hardcore {
    val SIGNED_IN = BoolSetting("Cheevos", "Enabled", false)
    val CHALLENGE_MODE = BoolSetting("Cheevos", "ChallengeMode", false)

    /** Read with a page whose rows say what hardcore mode turns off. */
    val settings: List<Setting<*>> = listOf(SIGNED_IN, CHALLENGE_MODE)

    const val OFF = "Off in hardcore mode"

    fun on(values: SettingValues) = values[SIGNED_IN] && values[CHALLENGE_MODE]
}
