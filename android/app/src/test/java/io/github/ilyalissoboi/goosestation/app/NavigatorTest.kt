// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.testGame
import io.github.ilyalissoboi.goosestation.ui.SettingsEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's screens and the moves between them ([Navigator]): which screen shows after a move, where the focus goes,
 * and which moves are refused. What the pages are told lands in one [log], in the order it came, with the screen as it
 * was then.
 */
class NavigatorTest {
    private val log = mutableListOf<String>()
    private val crash = testGame("Crash Bandicoot", path = "/games/crash.cue")
    private val noSerial = testGame("Homebrew", serial = "", path = "/games/homebrew.exe")

    private val pages =
        object : ScreenPages {
            override var ready = true
            override var gameActive = false
            override var hasConflicts = false

            /** The pages that can't close now. */
            val stuck = mutableSetOf<SettingsEntry>()

            override fun loadPage(entry: SettingsEntry) {
                log += "load $entry on ${navigator.screen}"
            }

            override fun closePage(entry: SettingsEntry): Boolean {
                if (entry in stuck) {
                    log += "close $entry refused"
                    return false
                }
                log += "close $entry"
                return true
            }

            override fun openSaves(game: GameEntry) {
                log += "openSaves ${game.title}"
            }

            override fun closeSaves(): String? {
                log += "closeSaves"
                return crash.path
            }

            override fun openGameSettings(game: GameEntry) {
                log += "openGameSettings ${game.title}"
            }

            override fun closeGameSettings(): String? {
                log += "closeGameSettings"
                return crash.path
            }

            override fun openMemcardEditor() {
                log += "openMemcardEditor"
            }

            override fun memcardEditorClosed() {
                log += "memcardEditorClosed"
            }

            override fun openSyncConflicts() {
                log += "openSyncConflicts"
            }

            override fun syncConflictsClosed() {
                log += "syncConflictsClosed"
            }

            override fun savesLeftForGame() {
                log += "savesLeftForGame"
            }
        }

    private val navigator: Navigator = Navigator(pages)

    /** The navigator on [entry]'s page, opened from the categories, and the [log] empty. */
    private fun onPage(entry: SettingsEntry): Navigator {
        navigator.openSettings()
        navigator.openSettingsPage(entry)
        log.clear()
        return navigator
    }

    // --- The settings ---

    @Test
    fun theSettingsOpenFromTheLibraryOnTheirCategories() {
        navigator.openSettings()

        assertEquals(Screen.Settings, navigator.screen)
        assertNull(navigator.settingsEntry)
        assertFalse(navigator.settingsInPage)
    }

    @Test
    fun theSettingsOpenOnlyFromTheLibrary() {
        navigator.showGame()
        navigator.openSettings()
        assertEquals(Screen.Game, navigator.screen)

        navigator.showSetup()
        navigator.openSettings()
        assertEquals(Screen.Setup, navigator.screen)
    }

    @Test
    fun closingTheSettingsFocusesTheLibrarysSettingsButton() {
        navigator.openSettings()

        navigator.closeSettings()

        assertEquals(Screen.Library, navigator.screen)
        assertTrue(navigator.focusSettings)
    }

    @Test
    fun aPageOpensFromTheCategoriesWithTheFocusInItOnceItIsRead() {
        navigator.openSettings()

        navigator.openSettingsPage(SettingsEntry.Graphics)

        // Read while the categories still showed, and only then the screen.
        assertEquals(listOf("load Graphics on Settings"), log)
        assertEquals(Screen.Graphics, navigator.screen)
        assertTrue(navigator.settingsInPage)
    }

    @Test
    fun aPageThatNeedsTheCoreWaitsForItAndForNoGame() {
        navigator.openSettings()

        pages.ready = false
        navigator.openSettingsPage(SettingsEntry.Graphics)
        pages.ready = true
        pages.gameActive = true
        navigator.openSettingsPage(SettingsEntry.Graphics)

        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.Settings, navigator.screen)
        assertFalse(navigator.settingsReady)
    }

    @Test
    fun theFoldersPageOpensBeforeTheCoreIsReady() {
        pages.ready = false
        navigator.openSettings()

        navigator.openSettingsPage(SettingsEntry.Folders)

        assertEquals(Screen.Folders, navigator.screen)
    }

    @Test
    fun aPageOpensOnlyFromTheCategories() {
        onPage(SettingsEntry.Graphics)

        navigator.openSettingsPage(SettingsEntry.Audio)

        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.Graphics, navigator.screen)
    }

    @Test
    fun aPageShownBesideTheCategoriesReplacesTheOpenOneOnceItHasClosed() {
        onPage(SettingsEntry.Graphics)

        navigator.showSettingsPage(SettingsEntry.Audio)

        assertEquals(listOf("close Graphics", "load Audio on Graphics"), log)
        assertEquals(Screen.Audio, navigator.screen)
        assertFalse(navigator.settingsInPage)
    }

    @Test
    fun aPageShownFromTheCategoriesAloneLoadsWithNothingToClose() {
        navigator.openSettings()

        navigator.showSettingsPage(SettingsEntry.Audio)

        assertEquals(listOf("load Audio on Settings"), log)
        assertEquals(Screen.Audio, navigator.screen)
        assertFalse(navigator.settingsInPage)
    }

    @Test
    fun theOpenPagesOwnCategoryOnlyTakesTheFocusBack() {
        onPage(SettingsEntry.Graphics)

        navigator.showSettingsPage(SettingsEntry.Graphics)

        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.Graphics, navigator.screen)
        assertFalse(navigator.settingsInPage)
    }

    @Test
    fun aPageThatCannotCloseKeepsItsPlace() {
        onPage(SettingsEntry.Folders)
        pages.stuck += SettingsEntry.Folders

        navigator.showSettingsPage(SettingsEntry.Audio)
        navigator.closeSettingsPage()
        navigator.leaveSettings()

        assertEquals(List(3) { "close Folders refused" }, log)
        assertEquals(Screen.Folders, navigator.screen)
        assertTrue(navigator.settingsInPage)
        assertFalse(navigator.focusSettings)
    }

    @Test
    fun aPageThatNeedsTheCoreIsNotShownBesideOneThatDoesNot() {
        pages.ready = false
        onPage(SettingsEntry.Folders)

        navigator.showSettingsPage(SettingsEntry.Graphics)

        // Checked before the open page is asked to close.
        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.Folders, navigator.screen)
    }

    @Test
    fun aPageIsShownBesideOnlyFromTheSettings() {
        navigator.showSettingsPage(SettingsEntry.Audio)

        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.Library, navigator.screen)
    }

    @Test
    fun backFromAPageFocusesItsCategory() {
        onPage(SettingsEntry.Graphics)

        navigator.closeSettingsPage()

        assertEquals(listOf("close Graphics"), log)
        assertEquals(Screen.Settings, navigator.screen)
        assertEquals(SettingsEntry.Graphics, navigator.settingsEntry)
        assertFalse(navigator.settingsInPage)
    }

    @Test
    fun backWithNoPageOpenDoesNothing() {
        navigator.openSettings()

        navigator.closeSettingsPage()

        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.Settings, navigator.screen)
        assertNull(navigator.settingsEntry)
    }

    @Test
    fun leavingTheSettingsFromAPageClosesItAndFocusesTheSettingsButton() {
        onPage(SettingsEntry.Covers)

        navigator.leaveSettings()

        assertEquals(listOf("close Covers"), log)
        assertEquals(Screen.Library, navigator.screen)
        assertTrue(navigator.focusSettings)
        assertFalse(navigator.settingsInPage)
    }

    @Test
    fun leavingTheSettingsFromTheCategoriesClosesThem() {
        navigator.openSettings()

        navigator.leaveSettings()

        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.Library, navigator.screen)
        assertTrue(navigator.focusSettings)
    }

    @Test
    fun leavingFromAScreenThatIsNotTheSettingsDoesNothing() {
        navigator.leaveSettings()

        assertEquals(Screen.Library, navigator.screen)
        assertFalse(navigator.focusSettings)
    }

    @Test
    fun enteringThePageMovesTheFocusIntoItButNotOnTheCategories() {
        navigator.openSettings()
        navigator.enterSettingsPage()
        assertFalse(navigator.settingsInPage)

        navigator.showSettingsPage(SettingsEntry.Audio)
        navigator.enterSettingsPage()

        assertTrue(navigator.settingsInPage)
    }

    // --- A game's Saves and its own settings ---

    @Test
    fun savesOpenFromTheLibraryAndBackFocusesTheGame() {
        navigator.openSaves(crash)
        assertEquals(Screen.Saves, navigator.screen)

        navigator.closeSaves()

        assertEquals(listOf("openSaves Crash Bandicoot", "closeSaves"), log)
        assertEquals(Screen.Library, navigator.screen)
        assertEquals(crash.path, navigator.libraryFocus)
        assertFalse(navigator.focusSettings)
    }

    @Test
    fun savesDoNotOpenBeforeTheAppIsReadyDuringAGameOrAwayFromTheLibrary() {
        pages.ready = false
        navigator.openSaves(crash)
        pages.ready = true
        pages.gameActive = true
        navigator.openSaves(crash)
        pages.gameActive = false
        navigator.openSettings()
        navigator.openSaves(crash)

        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.Settings, navigator.screen)
    }

    @Test
    fun aGamesSettingsOpenFromTheLibraryAndBackFocusesTheGameNotTheSettingsButton() {
        navigator.openSettings()
        navigator.closeSettings()
        assertTrue(navigator.focusSettings)

        navigator.openGameSettings(crash)
        assertEquals(Screen.GameSettings, navigator.screen)
        navigator.closeGameSettings()

        assertEquals(listOf("openGameSettings Crash Bandicoot", "closeGameSettings"), log)
        assertEquals(Screen.Library, navigator.screen)
        assertEquals(crash.path, navigator.libraryFocus)
        assertFalse(navigator.focusSettings)
    }

    @Test
    fun aGamesSettingsNeedTheSettingsReadyAndASerial() {
        pages.gameActive = true
        navigator.openGameSettings(crash)
        pages.gameActive = false
        navigator.openGameSettings(noSerial)

        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.Library, navigator.screen)
    }

    @Test
    fun closingAScreenThatIsNotShownDoesNothing() {
        navigator.closeSaves()
        navigator.closeGameSettings()
        navigator.closeMemcardEditor()
        navigator.closeSyncConflicts()
        navigator.closeSettings()

        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.Library, navigator.screen)
        assertFalse(navigator.focusSettings)
    }

    // --- Under Memory cards ---

    @Test
    fun theMemcardEditorOpensFromMemoryCardsAndBackCountsTheCardsAgain() {
        onPage(SettingsEntry.MemoryCards)

        navigator.openMemcardEditor()
        assertEquals(Screen.MemcardEditor, navigator.screen)
        // The page stays the one whose screen opened it.
        assertTrue(navigator.settingsInPage)
        navigator.closeMemcardEditor()

        assertEquals(listOf("openMemcardEditor", "memcardEditorClosed"), log)
        assertEquals(Screen.MemoryCards, navigator.screen)
    }

    @Test
    fun theMemcardEditorOpensOnlyFromMemoryCardsWithTheSettingsReady() {
        navigator.openMemcardEditor()
        onPage(SettingsEntry.MemoryCards)
        pages.gameActive = true
        navigator.openMemcardEditor()

        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.MemoryCards, navigator.screen)
    }

    @Test
    fun theConflictsGoBackToMemoryCardsWhenOpenedFromThere() {
        onPage(SettingsEntry.MemoryCards)

        navigator.openSyncConflicts()
        assertEquals(Screen.SyncConflicts, navigator.screen)
        navigator.closeSyncConflicts()

        assertEquals(listOf("openSyncConflicts", "syncConflictsClosed"), log)
        assertEquals(Screen.MemoryCards, navigator.screen)
    }

    @Test
    fun theConflictsGoBackToTheLibraryWhenOpenedFromThere() {
        onPage(SettingsEntry.MemoryCards)
        navigator.openSyncConflicts()
        navigator.closeSyncConflicts()
        navigator.leaveSettings()

        navigator.openSyncConflicts()
        navigator.closeSyncConflicts()

        assertEquals(Screen.Library, navigator.screen)
    }

    @Test
    fun theConflictsOpenOnlyFromMemoryCardsOrTheLibraryWithTheSettingsReady() {
        navigator.openSettings()
        navigator.openSyncConflicts()
        navigator.closeSettings()
        pages.ready = false
        navigator.openSyncConflicts()

        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.Library, navigator.screen)
    }

    @Test
    fun theSyncBadgeOpensTheConflictsWhenThereAreAny() {
        pages.hasConflicts = true

        navigator.openSyncBadge()
        navigator.closeSyncConflicts()

        assertEquals(listOf("openSyncConflicts", "syncConflictsClosed"), log)
        assertEquals(Screen.Library, navigator.screen)
    }

    @Test
    fun theSyncBadgeOtherwiseOpensMemoryCardsWithTheFocusInThePage() {
        navigator.openSyncBadge()

        assertEquals(listOf("load MemoryCards on Settings"), log)
        assertEquals(Screen.MemoryCards, navigator.screen)
        assertTrue(navigator.settingsInPage)
        assertNull(navigator.settingsEntry)
    }

    @Test
    fun theSyncBadgeDoesNothingAwayFromTheLibraryOrDuringAGame() {
        pages.gameActive = true
        navigator.openSyncBadge()
        pages.gameActive = false
        navigator.openSettings()
        navigator.openSyncBadge()

        assertEquals(emptyList<String>(), log)
        assertEquals(Screen.Settings, navigator.screen)
    }

    // --- The app's own moves ---

    @Test
    fun aGameStartClearsTheLibrarysFocusHints() {
        navigator.openSaves(crash)
        navigator.closeSaves()
        navigator.openSettings()
        navigator.closeSettings()
        log.clear()

        navigator.gameBegins()
        navigator.showGame()

        assertEquals(emptyList<String>(), log)
        assertNull(navigator.libraryFocus)
        assertFalse(navigator.focusSettings)
        assertEquals(Screen.Game, navigator.screen)
    }

    @Test
    fun aGameStartFromSavesClosesThem() {
        navigator.openSaves(crash)
        log.clear()

        navigator.gameBegins()

        assertEquals(listOf("savesLeftForGame"), log)
    }

    @Test
    fun leavingTheGameShowsTheLibrary() {
        navigator.showGame()

        navigator.showLibrary()

        assertEquals(Screen.Library, navigator.screen)
    }

    @Test
    fun theFolderSettingsFromABannerOpenWithTheFocusInThePage() {
        navigator.showFolders()

        assertEquals(Screen.Folders, navigator.screen)
        assertTrue(navigator.settingsInPage)
        // Read by the page itself: nothing to load.
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun setupsFinishGoesAheadOnceAndOnlyFromSetup() {
        assertFalse(navigator.finishSetup())

        navigator.showSetup()
        assertTrue(navigator.finishSetup())
        assertEquals(Screen.Library, navigator.screen)

        assertFalse(navigator.finishSetup())
    }
}
