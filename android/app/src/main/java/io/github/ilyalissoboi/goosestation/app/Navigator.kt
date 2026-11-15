// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.ui.SettingsEntry

/**
 * The app's screens: the settings' categories with no page open ([Screen.Settings], the list a narrow window shows),
 * and their pages ([Screen.page]), open from the library.
 */
enum class Screen(
    /** The hub's entry for this page of the settings; null for any other screen. */
    val page: SettingsEntry? = null,
) {
    Setup,
    Library,
    Settings,
    Controllers(SettingsEntry.Controllers),
    Interface(SettingsEntry.Interface),
    Console(SettingsEntry.Console),
    Emulation(SettingsEntry.Emulation),
    Graphics(SettingsEntry.Graphics),
    PostProcessing(SettingsEntry.PostProcessing),
    Audio(SettingsEntry.Audio),
    Achievements(SettingsEntry.Achievements),
    Advanced(SettingsEntry.Advanced),
    MemoryCards(SettingsEntry.MemoryCards),
    Bios(SettingsEntry.Bios),
    Folders(SettingsEntry.Folders),
    Covers(SettingsEntry.Covers),
    SyncConflicts,

    /** The memory card editor, from the Memory cards page. */
    MemcardEditor,

    /** A game's own settings, from its tile in the library. */
    GameSettings,

    /** A game's Saves, from its tile in the library. */
    Saves,
    Game,
}

/** What [Navigator] asks of the pages and the app, or tells them. Main thread only. */
interface ScreenPages {
    /** The core is initialized, and the data folder pulled in full. */
    val ready: Boolean

    /** A game from a start isn't over yet. */
    val gameActive: Boolean

    /** Memory card sync has conflicts waiting for the player. */
    val hasConflicts: Boolean

    /** Reads what [entry]'s page shows, before it becomes the screen. */
    fun loadPage(entry: SettingsEntry)

    /**
     * Closes [entry]'s page, for another screen to take its place; false if it can't close (Folders, while a data
     * folder change is under way), and nothing else changes.
     */
    fun closePage(entry: SettingsEntry): Boolean

    fun openSaves(game: GameEntry)

    /** Closes the Saves page, and returns its game's path, for the library to focus. */
    fun closeSaves(): String?

    fun openGameSettings(game: GameEntry)

    /** Closes a game's own settings, and returns its path, for the library to focus. */
    fun closeGameSettings(): String?

    fun openMemcardEditor()

    /** Back from the editor: the Memory cards page counts the cards again. */
    fun memcardEditorClosed()

    fun openSyncConflicts()

    fun syncConflictsClosed()

    /** A game starts from the Saves page, which closes. */
    fun savesLeftForGame()
}

/**
 * Which screen shows, and where the focus goes on the next one: the settings' categories and pages, a game's Saves
 * and its own settings, the memory card editor and sync's conflicts, and the moves the game, the folder settings and
 * the cold start make. Each move checks it can be made from where the player is, and does nothing otherwise. What
 * each page reads or lets go of stays with the pages ([ScreenPages]). Main thread only, every field and function.
 */
class Navigator(
    private val pages: ScreenPages,
) {
    /** The screen shown. The UI reads it. */
    var screen by mutableStateOf(Screen.Library)
        private set

    /** The library focuses its Settings button when it opens, as it does coming back from the settings. */
    var focusSettings by mutableStateOf(false)
        private set

    /** The category of the page last left, which the settings focus; null on opening them from the library. */
    var settingsEntry by mutableStateOf<SettingsEntry?>(null)
        private set

    /**
     * In the settings' two panels, the focus is in the open page, not on its category beside it: set on going into
     * the page, or opening it from elsewhere, and kept while a screen the page opened shows.
     */
    var settingsInPage by mutableStateOf(false)
        private set

    /** The game the library focuses next, over the last one launched: the one whose settings were just shown. */
    var libraryFocus by mutableStateOf<String?>(null)
        private set

    // Where the conflict screen was opened from, which it goes back to.
    private var conflictsFrom = Screen.MemoryCards

    /** The settings pages that need the core can open: it has started, and no game is under way. */
    val settingsReady get() = pages.ready && !pages.gameActive

    // --- The app's own moves ---

    /** Setup shows: there are no folders yet. */
    fun showSetup() {
        screen = Screen.Setup
    }

    /** Setup is done; false if it was already, so a second Finish, pressed before the library shows, does nothing. */
    fun finishSetup(): Boolean {
        if (screen != Screen.Setup) return false
        screen = Screen.Library
        return true
    }

    /** The library shows: at the cold start, or as the game is left. */
    fun showLibrary() {
        screen = Screen.Library
    }

    /** The game takes the screen, from the library or Saves. */
    fun showGame() {
        screen = Screen.Game
    }

    /** A game starts: the library will focus it, not what was focused before, and Saves closes if it showed. */
    fun gameBegins() {
        libraryFocus = null
        focusSettings = false
        if (screen == Screen.Saves) pages.savesLeftForGame()
    }

    /** The folder settings, with the focus in the page: a data folder picked from a library banner waits there. */
    fun showFolders() {
        settingsInPage = true
        screen = Screen.Folders
    }

    // --- The settings ---

    /** Opens the settings, from the library, on their categories. */
    fun openSettings() {
        if (screen != Screen.Library) return
        settingsEntry = null
        settingsInPage = false
        screen = Screen.Settings
    }

    /** Back to the library, which focuses its Settings button. */
    fun closeSettings() {
        if (screen != Screen.Settings) return
        focusSettings = true
        screen = Screen.Library
    }

    /**
     * Opens [entry]'s page from the categories' list, with the focus in it, if it can: all but Folders need
     * [settingsReady].
     */
    fun openSettingsPage(entry: SettingsEntry) {
        if (screen != Screen.Settings || (entry.needsCore && !settingsReady)) return
        loadSettingsPage(entry)
        settingsInPage = true
    }

    /**
     * Shows [entry]'s page beside the categories, in the settings' two panels, with the focus on its category: in
     * place of the page that's open, if one is and it can close. The open page's own category only takes the focus
     * back from it.
     */
    fun showSettingsPage(entry: SettingsEntry) {
        if (screen != Screen.Settings && screen.page == null) return
        if (screen.page != entry) {
            if (entry.needsCore && !settingsReady) return
            if (screen.page != null && unloadSettingsPage() == null) return
            loadSettingsPage(entry)
        }
        settingsInPage = false
    }

    /** The focus goes into the open page, in the settings' two panels. */
    fun enterSettingsPage() {
        if (screen.page != null) settingsInPage = true
    }

    /** Back from a settings page to the categories' list, which focuses the page's entry, if the page can close. */
    fun closeSettingsPage() {
        val page = unloadSettingsPage() ?: return
        settingsEntry = page
        settingsInPage = false
        screen = Screen.Settings
    }

    /** Back to the library from the settings, with a page open or not, if the page can close. */
    fun leaveSettings() {
        if (screen == Screen.Settings) return closeSettings()
        unloadSettingsPage() ?: return
        settingsInPage = false
        focusSettings = true
        screen = Screen.Library
    }

    // Reads what entry's page shows, and makes it the screen.
    private fun loadSettingsPage(entry: SettingsEntry) {
        pages.loadPage(entry)
        screen = Screen.entries.first { it.page == entry }
    }

    // Closes the open settings page, for another screen to take its place: its category, or null with no page open,
    // or one that can't close (Folders, while a data folder change is under way).
    private fun unloadSettingsPage(): SettingsEntry? {
        val page = screen.page ?: return null
        return page.takeIf { pages.closePage(it) }
    }

    // --- A game's Saves and its own settings ---

    /** Opens [game]'s Saves, from the library. */
    fun openSaves(game: GameEntry) {
        if (screen != Screen.Library || !pages.ready || pages.gameActive) return
        pages.openSaves(game)
        screen = Screen.Saves
    }

    /** Back to the library, which focuses the game. */
    fun closeSaves() {
        if (screen != Screen.Saves) return
        libraryFocus = pages.closeSaves()
        focusSettings = false
        screen = Screen.Library
    }

    /** Opens [game]'s own settings, from the library, if the settings can open and it has a key to keep them under. */
    fun openGameSettings(game: GameEntry) {
        if (screen != Screen.Library || !settingsReady || game.serial.isBlank()) return
        pages.openGameSettings(game)
        screen = Screen.GameSettings
    }

    /** Back to the library, which focuses the game. */
    fun closeGameSettings() {
        if (screen != Screen.GameSettings) return
        libraryFocus = pages.closeGameSettings()
        focusSettings = false
        screen = Screen.Library
    }

    // --- Under Memory cards ---

    /** Opens the memory card editor, from the Memory cards page, if the settings can open: no game is under way. */
    fun openMemcardEditor() {
        if (screen != Screen.MemoryCards || !settingsReady) return
        pages.openMemcardEditor()
        screen = Screen.MemcardEditor
    }

    /** Back from the editor to the Memory cards page, which counts the cards again. */
    fun closeMemcardEditor() {
        if (screen != Screen.MemcardEditor) return
        pages.memcardEditorClosed()
        screen = Screen.MemoryCards
    }

    /** Opens the conflict screen, from the Memory cards page or the library's badge, if the settings can open. */
    fun openSyncConflicts() {
        if ((screen != Screen.MemoryCards && screen != Screen.Library) || !settingsReady) return
        conflictsFrom = screen
        pages.openSyncConflicts()
        screen = Screen.SyncConflicts
    }

    /** The library's sync badge: the conflict screen when there are conflicts, else Settings > Memory cards. */
    fun openSyncBadge() {
        if (screen != Screen.Library || !settingsReady) return
        if (pages.hasConflicts) {
            openSyncConflicts()
        } else {
            settingsEntry = null
            screen = Screen.Settings
            // With the focus in the page, which is what the badge is about.
            openSettingsPage(SettingsEntry.MemoryCards)
        }
    }

    /** Back from the conflict screen to where it was opened from. */
    fun closeSyncConflicts() {
        if (screen != Screen.SyncConflicts) return
        pages.syncConflictsClosed()
        screen = conflictsFrom
    }
}
