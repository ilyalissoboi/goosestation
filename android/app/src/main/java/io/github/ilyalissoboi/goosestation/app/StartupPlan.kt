// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.storage.MissingGrant
import io.github.ilyalissoboi.goosestation.storage.SavedFolders

/** A step of a start or a resume, which [AppController] carries out in order. */
enum class StartupStep { ShowSetup, Pull, InitializeCore, SetGameFolders, RefreshLibrary, ReloadSettings, ReadCorePaths }

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
     * adapted on reading, loses the game folders, so they're set again and the library is scanned. Otherwise, an
     * `.ini` it replaced in a folder (a game's settings, which can name its own memory card, wherever settings.ini
     * puts their folder) has the core's paths read again.
     */
    fun afterPull(changed: Set<String>): List<StartupStep> =
        when {
            SETTINGS in changed ->
                listOf(StartupStep.ReloadSettings, StartupStep.SetGameFolders, StartupStep.RefreshLibrary)
            changed.any { '/' in it && it.endsWith(INI) } -> listOf(StartupStep.ReadCorePaths)
            else -> emptyList()
        }

    /**
     * Carries out [steps] in order with [perform], which returns whether the step grew the data layout (reading the
     * core's paths can). [onGrowth] runs as soon as one has, before the next step: a library scan can take a while,
     * and nothing may use the grown layout meanwhile as if it were complete. Returns whether any step grew it.
     */
    inline fun carryOut(
        steps: List<StartupStep>,
        onGrowth: () -> Unit,
        perform: (StartupStep) -> Boolean,
    ): Boolean {
        var grew = false
        for (step in steps) {
            if (perform(step)) {
                grew = true
                onGrowth()
            }
        }
        return grew
    }

    private const val SETTINGS = "settings.ini"
    private const val INI = ".ini"
}
