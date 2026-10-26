// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.view.Surface

/**
 * The emulator core, in libgoosestation.so. The core runs on its own thread: these calls queue work for it and
 * return straight away, except [initialize], [surfaceChanged] and [reloadSettings], which wait for it.
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

    /** Registers the callbacks, or removes them with null. They're called on the core's threads. */
    external fun setCallbacks(callbacks: NativeCallbacks?)

    /** Boots a game once a surface exists; the result arrives through [NativeCallbacks], ending with onGameStopped(). */
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
