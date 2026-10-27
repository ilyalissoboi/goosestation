// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.app.StartupStep.InitializeCore
import io.github.ilyalissoboi.goosestation.app.StartupStep.Pull
import io.github.ilyalissoboi.goosestation.app.StartupStep.ReadCorePaths
import io.github.ilyalissoboi.goosestation.app.StartupStep.RefreshLibrary
import io.github.ilyalissoboi.goosestation.app.StartupStep.ReloadSettings
import io.github.ilyalissoboi.goosestation.app.StartupStep.SetGameFolders
import io.github.ilyalissoboi.goosestation.app.StartupStep.ShowSetup
import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.storage.MissingGrant
import io.github.ilyalissoboi.goosestation.storage.SavedFolders
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPlanTest {
    private val bios = "content://a.b/tree/primary%3APS1%2Fbios"
    private val games = "content://a.b/tree/primary%3APS1%2FGames"
    private val data = "content://a.b/tree/primary%3APS1%2Fdata"
    private val setUp = SavedFolders(bios = bios, games = listOf(games), data = data, setupComplete = true)

    @Test
    fun notSetUpShowsSetup() {
        val notSetUp = SavedFolders(bios = null, games = emptyList(), data = null, setupComplete = false)

        assertEquals(listOf(ShowSetup), StartupPlan.steps(notSetUp, emptyList(), coreStarted = false))
    }

    @Test
    fun coldStartWithEveryGrantPullsFirst() {
        assertEquals(
            listOf(Pull, InitializeCore, SetGameFolders, RefreshLibrary),
            StartupPlan.steps(setUp, emptyList(), coreStarted = false),
        )
    }

    @Test
    fun coldStartWithoutTheDataGrantSkipsThePull() {
        val missing = listOf(MissingGrant(FolderKind.Data, data))

        assertEquals(
            listOf(InitializeCore, SetGameFolders, RefreshLibrary),
            StartupPlan.steps(setUp, missing, coreStarted = false),
        )
    }

    @Test
    fun coldStartWithoutAGamesGrantStillPulls() {
        val missing = listOf(MissingGrant(FolderKind.Games, games))

        assertEquals(
            listOf(Pull, InitializeCore, SetGameFolders, RefreshLibrary),
            StartupPlan.steps(setUp, missing, coreStarted = false),
        )
    }

    @Test
    fun resumePullsAndRefreshes() {
        assertEquals(listOf(Pull, RefreshLibrary), StartupPlan.steps(setUp, emptyList(), coreStarted = true))
    }

    @Test
    fun resumeWithoutTheDataGrantOnlyRefreshes() {
        val missing = listOf(MissingGrant(FolderKind.Data, data))

        assertEquals(listOf(RefreshLibrary), StartupPlan.steps(setUp, missing, coreStarted = true))
    }

    @Test
    fun aPullThatChangedSettingsReloadsThemThenTheLibrary() {
        // A reload of a foreign settings.ini drops the game folders, so they're set again and the library rescanned.
        val reload = listOf(ReloadSettings, SetGameFolders, RefreshLibrary)

        assertEquals(reload, StartupPlan.afterPull(setOf("settings.ini")))
        assertEquals(reload, StartupPlan.afterPull(setOf("memcards/a.mcd", "settings.ini")))
        // The reload reads the core's paths again anyway.
        assertEquals(reload, StartupPlan.afterPull(setOf("gamesettings/SLUS-00001.ini", "settings.ini")))
    }

    // A game's settings can name its own memory card, which the core's paths then hold: in gamesettings/, or wherever
    // settings.ini puts that folder.
    @Test
    fun aPullThatChangedGameSettingsReadsTheCorePathsAgain() {
        val read = listOf(ReadCorePaths)

        assertEquals(read, StartupPlan.afterPull(setOf("gamesettings/HASH-9FDA0E1A56D8DF5B.ini")))
        assertEquals(read, StartupPlan.afterPull(setOf("memcards/a.mcd", "my/gamesettings/SLUS-00001.ini")))
        assertEquals(read, StartupPlan.afterPull(setOf("gamesettings/settings.ini")))
    }

    @Test
    fun aPullThatLeftSettingsAloneReloadsNothing() {
        assertEquals(emptyList<StartupStep>(), StartupPlan.afterPull(setOf("memcards/a.mcd")))
        assertEquals(emptyList<StartupStep>(), StartupPlan.afterPull(setOf("secrets.ini", "custom_properties.ini")))
        assertEquals(emptyList<StartupStep>(), StartupPlan.afterPull(setOf("gamesettings/SLUS-00001.txt")))
        assertEquals(emptyList<StartupStep>(), StartupPlan.afterPull(emptySet()))
    }
}
