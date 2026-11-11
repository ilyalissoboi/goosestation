// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.NativeLibrary

/**
 * The core's calls the mirror's runs make ([DataSync]), and no others: [NativeSyncCore] in the app, a fake in a JVM
 * test. Each runs on the thread [NativeLibrary]'s own says.
 */
interface SyncCore {
    fun reloadSettings()

    fun getMemoryCardPaths(): List<String>?

    fun getDataFolders(): List<String>?

    fun getDataFiles(): List<String>?

    fun getCacheFolder(): String?

    fun setGameFolders(folders: Array<String>)

    fun refreshGameList(invalidateCache: Boolean)

    fun getGameList(): List<GameEntry>

    /** [NativeLibrary.memcardSyncHoldCard]: runs [action] with the card at [path] held, or returns false. */
    fun holdCard(
        path: String,
        action: () -> Unit,
    ): Boolean

    fun getShadersFolder(): String?

    fun shaderPackIds(): List<String>

    fun shaderPackMarkerFiles(json: String): List<String>?
}

/** [SyncCore] on [NativeLibrary], with its arrays as lists. */
object NativeSyncCore : SyncCore {
    override fun reloadSettings() = NativeLibrary.reloadSettings()

    override fun getMemoryCardPaths() = NativeLibrary.getMemoryCardPaths()?.toList()

    override fun getDataFolders() = NativeLibrary.getDataFolders()?.toList()

    override fun getDataFiles() = NativeLibrary.getDataFiles()?.toList()

    override fun getCacheFolder() = NativeLibrary.getCacheFolder()

    override fun setGameFolders(folders: Array<String>) = NativeLibrary.setGameFolders(folders)

    override fun refreshGameList(invalidateCache: Boolean) = NativeLibrary.refreshGameList(invalidateCache)

    override fun getGameList() = NativeLibrary.getGameList().toList()

    override fun holdCard(
        path: String,
        action: () -> Unit,
    ) = NativeLibrary.memcardSyncHoldCard(path) { action() }

    override fun getShadersFolder() = NativeLibrary.getShadersFolder()

    override fun shaderPackIds() = NativeLibrary.shaderPackIds().toList()

    override fun shaderPackMarkerFiles(json: String) = NativeLibrary.shaderPackMarkerFiles(json)?.toList()
}

/** What the mirror's runs ([DataSync]) tell the app, or ask it. On the main thread, but where one says otherwise. */
interface SyncHooks {
    /** Whether the core has started. Read on the main thread, and on the mirror's by the card guard. */
    fun coreStarted(): Boolean

    /** Whether the data folder's grant is there. */
    fun dataGranted(): Boolean

    /** The games folders the app can read, set again after settings.ini comes in. */
    fun readableGameFolders(): Array<String>

    /** On the core's thread, right after settings.ini is read again: what the app reads of it (the library view). */
    suspend fun onSettingsReloadedOnCore()

    /** The library, as the rescan that followed a pull found it. */
    fun onLibraryRead(games: List<GameEntry>)

    /** After a pull or push's [steps] are carried out: the pages shown read their settings again. */
    fun onSettingsReloaded(steps: List<StartupStep>)

    /** The working copy changed: the memory card editor's view of it is read again. */
    fun refreshEditor()

    /**
     * The message line says the data is being copied in: "Copying…", or with [progress] (the action under way, of
     * how many) after it. Only the line: not whether it's an error.
     */
    fun showCopying(progress: Pair<Int, Int>?)

    /** Whether the message line says just "Copying…". */
    fun copyingShown(): Boolean

    /** Clears the message line. */
    fun clearCopying()
}
