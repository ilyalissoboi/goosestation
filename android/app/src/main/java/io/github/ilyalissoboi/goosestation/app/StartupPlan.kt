// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.storage.MissingGrant
import io.github.ilyalissoboi.goosestation.storage.SavedFolders

/** A step of a start or a resume, which [AppController] carries out in order. */
enum class StartupStep { ShowSetup, Pull, InitializeCore, SetGameFolders, RefreshLibrary, ReloadSettings }

/** What a start or a resume does, from the saved folders and the grants they're missing. */
object StartupPlan {
    /**
     * Not set up: the setup screen. Otherwise a pull from the data folder, if its grant is held (before the core
     * starts, since it reads settings.ini then), and then either the core's start ([coreStarted] false) or, on a
     * resume, a refresh of the library. Without the data grant the core works on the working copy alone.
     */
    fun steps(
        saved: SavedFolders,
        missing: List<MissingGrant>,
        coreStarted: Boolean,
    ): List<StartupStep> {
        if (!saved.setupComplete) return listOf(StartupStep.ShowSetup)
        val canPull = saved.data != null && missing.none { it.kind == FolderKind.Data }
        return buildList {
            if (canPull) add(StartupStep.Pull)
            if (!coreStarted) {
                add(StartupStep.InitializeCore)
                add(StartupStep.SetGameFolders)
            }
            add(StartupStep.RefreshLibrary)
        }
    }

    /**
     * What follows a pull or a push, once the core runs: a settings.ini it replaced is read again. A foreign one,
     * adapted on reading, loses the game folders, so they're set again and the library is scanned.
     */
    fun afterPull(changed: Set<String>): List<StartupStep> =
        if (SETTINGS in changed) {
            listOf(StartupStep.ReloadSettings, StartupStep.SetGameFolders, StartupStep.RefreshLibrary)
        } else {
            emptyList()
        }

    private const val SETTINGS = "settings.ini"
}
