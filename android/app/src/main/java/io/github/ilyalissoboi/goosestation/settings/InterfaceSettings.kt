// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

/** The Interface page's settings, by settings.ini's names, each with the core's Android default. */
object InterfaceSettings {
    val START_PAUSED = BoolSetting("Main", "StartPaused", false)
    val PAUSE_ON_DISCONNECT = BoolSetting("Main", "PauseOnControllerDisconnection", false)
    val SAVE_STATE_ON_EXIT = BoolSetting("Main", "SaveStateOnExit", true)

    /** Off by default on Android (`Settings::DEFAULT_SAVE_STATE_BACKUPS`), on on desktop. */
    val SAVE_STATE_BACKUPS = BoolSetting("Main", "CreateSaveStateBackups", false)

    val all: List<Setting<*>> = listOf(START_PAUSED, PAUSE_ON_DISCONNECT, SAVE_STATE_ON_EXIT, SAVE_STATE_BACKUPS)
}
