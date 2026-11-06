// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

/**
 * The settings a running game takes only when it next starts (`System::CheckForSettingsChanges` leaves them to the
 * boot), and what their rows say while it runs.
 */
object NextStart {
    val settings: Set<Setting<*>> =
        setOf(
            ConsoleSettings.REGION,
            ConsoleSettings.RAM_8MB,
            ConsoleSettings.PRELOAD,
            ConsoleSettings.IMAGE_PATCHES,
            // The BIOS checks the disc's region as it boots it; a missing SBI file is checked at the boot.
            AdvancedSettings.REGION_CHECK,
            AdvancedSettings.BOOT_WITHOUT_SBI,
        )

    const val NOTE = "Applies when the game next starts."

    /** Hardcore mode turned on waits for a reset or the next start; turned off, it's at once. */
    const val HARDCORE_NOTE = "Turning it on applies when the game next starts."
}
