// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * Events from the core, registered with [NativeLibrary.setCallbacks]. They're called on the core's threads, not the
 * UI's: post to the UI thread, and never wait for it (it may be waiting for the core in [NativeLibrary.surfaceChanged]).
 */
interface NativeCallbacks {
    /** The game from [NativeLibrary.startGame] booted. */
    fun onGameStarted(title: String)

    /**
     * The game from [NativeLibrary.startGame] is gone. Every startGame() ends with exactly one call: when the game
     * stops, or without [onGameStarted] when it fails to boot (after [onError]), is refused, or is cancelled before it
     * boots.
     */
    fun onGameStopped()

    /** Something to show the user. A failed boot can report more than one; it doesn't end the game by itself. */
    fun onError(message: String)

    /**
     * The input [NativeLibrary.startBindingCapture] captured, as a [PadBinding.binding] for [NativeLibrary.setPadBinding]:
     * Button99, +Axis23, -Axis0. Each capture ends with at most one call, and none once it's cancelled or replaced.
     */
    fun onBindingCaptured(binding: String) {}

    /**
     * An on-screen message from the core: its [text] (icon characters gone), shown for [seconds], replacing any with
     * the same [key] (empty for none), and its [icon], if it has one. [seconds] 0 takes [key]'s message away.
     */
    fun onOsdMessage(
        key: String,
        text: String,
        seconds: Float,
        icon: ArgbImage?,
    ) {}

    /** Fast forward was turned on or off, by the menu or a hotkey. */
    fun onFastForwardChanged(enabled: Boolean) {}
}
