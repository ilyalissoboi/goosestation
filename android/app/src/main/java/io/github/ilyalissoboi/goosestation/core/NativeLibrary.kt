// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.view.Surface

/**
 * The emulator core, in libgoosestation.so. The core runs on its own thread: these calls queue work for it and
 * return straight away, except [initialize], [surfaceChanged] and [reloadSettings], which wait for it, and
 * [refreshGameList], which scans on the calling thread.
 */
object NativeLibrary {
    init {
        System.loadLibrary("goosestation")
    }

    /**
     * Loads the settings from [dataRoot] and starts the core. Call it first; later calls do nothing. BIOS images are
     * read from [biosDir]. A settings.ini made by anything but this install ([installId]) has its device-specific
     * settings reset.
     */
    external fun initialize(dataRoot: String, resourcesRoot: String, biosDir: String, installId: String): Boolean

    // The settings calls work on settings.ini, which they save, and can be made from any thread.

    /** The game list scans exactly these folders, and every folder below them. */
    external fun setGameFolders(treeUris: Array<String>)

    /** Reads settings.ini again, after the app replaced it, and applies it. Waits for the core. */
    external fun reloadSettings()

    external fun getBoolSetting(section: String, key: String, default: Boolean): Boolean

    external fun setBoolSetting(section: String, key: String, value: Boolean)

    /**
     * Where the memory cards are, relative to the data root: the memory card folder with a trailing `/` (empty if
     * it's the data root itself), then each shared card file set in settings.ini that's elsewhere in the data root.
     * Paths outside the data root are left out. Null before [initialize].
     */
    external fun getMemoryCardPaths(): Array<String>?

    /**
     * Copies the PS1 BIOS images the core recognises from the top of [sourceTreeUri] to [destDir], then deletes every
     * other file in [destDir]. Returns the images' descriptions in file name order. A folder with no image gives an
     * empty array, and nothing is deleted. Returns null if the folder can't be read or a copy fails, and then nothing
     * is deleted either. Doesn't need [initialize]. It runs on the calling thread and does I/O, so call it off the
     * main thread.
     */
    external fun importBios(sourceTreeUri: String, destDir: String): Array<String>?

    /**
     * Scans the game folders ([setGameFolders]) for games, reading each folder afresh. A file whose path and modified
     * time are in the game list cache isn't opened again, unless [invalidateCache]. Does nothing before [initialize].
     * It blocks while it reads the folders, so call it on `Dispatchers.IO`.
     */
    external fun refreshGameList(invalidateCache: Boolean)

    /**
     * The games the last [refreshGameList] found, sorted by title (case-insensitively), each disc of a multi-disc set
     * on its own. Empty if the list can't be passed on (it's logged). Call it only once [refreshGameList] has
     * returned: during a refresh it gives the part found so far. It checks for cover images, so call it off the main
     * thread.
     */
    external fun getGameList(): Array<GameEntry>

    /** Registers the callbacks, or removes them with null. They're called on the core's threads. */
    external fun setCallbacks(callbacks: NativeCallbacks?)

    /**
     * Boots a game once a surface exists; the result arrives through [NativeCallbacks], ending with onGameStopped().
     * [path] is a [GameEntry.path], content path or not, as it is.
     */
    external fun startGame(path: String)

    external fun shutdownGame()

    external fun setPaused(paused: Boolean)

    /** A new or resized surface, or null when it's destroyed. Returns once the core has stopped using the old one. */
    external fun surfaceChanged(surface: Surface?, width: Int, height: Int, scale: Float)

    /** A controller key; returns whether it went to the emulated pad. */
    external fun onKey(deviceId: Int, keyCode: Int, pressed: Boolean): Boolean

    external fun onAxis(deviceId: Int, axis: Int, value: Float)

    /** Report every controller before its input, including those present at start. */
    external fun onDeviceConnected(deviceId: Int, name: String, descriptor: String)

    external fun onDeviceDisconnected(deviceId: Int)

    /** Lets go of every controller input, after the input sent before it. */
    external fun releaseAllInputs()
}
