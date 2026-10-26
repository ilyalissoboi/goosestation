// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * Events from the core, registered with [NativeLibrary.setCallbacks]. They're called on the core's threads, not the
 * UI's: post to the UI thread, and never wait for it (it may be waiting for the core in [NativeLibrary.surfaceChanged]).
 */
interface NativeCallbacks {
    fun onGameStarted(title: String)

    fun onGameStopped()

    fun onError(message: String)
}
