// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.NativeLibrary

/** A game's save state slots, as the core numbers them. */
object SaveSlots {
    /** The resume state's slot, saved by Exit game. */
    const val RESUME = -1

    /** Slots 1 to [COUNT] per game. */
    const val COUNT = 10
}

/**
 * The core's calls for the running game and its save states, as [NativeLibrary] has them ([NativeGameCoreApi]), so
 * the in-game menu can be tested with a fake. Every call is made on `AppController.gameDispatcher`: the first five
 * block until the core has run them.
 */
interface GameCoreApi {
    /** When each of [serial]'s states was saved, in Unix seconds, 0 for none: `[0]` the resume state, `[n]` slot n. */
    fun getSaveStateTimes(serial: String): LongArray

    fun getSaveStateScreenshot(
        serial: String,
        slot: Int,
    ): ArgbImage?

    /** Null when it's saved, or else why not. */
    fun saveState(slot: Int): String?

    /** Null when it's loaded, or else why not. */
    fun loadState(slot: Int): String?

    /** Whether Exit game saves a resume state: `[Main] SaveStateOnExit`. */
    fun savesResumeState(): Boolean

    /** Saves the running game's resume state for Exit game: null when it's saved or there's none to save, else why not. */
    fun saveResumeState(): String?

    /** -1 if Port 1 isn't a DualShock, 0 digital, 1 analog. */
    fun getAnalogMode(): Int

    /** The game is writing to a memory card: a reset or an exit now can damage that save. */
    fun isSavingMemoryCards(): Boolean

    fun resetGame()

    fun setFastForward(enabled: Boolean)

    fun toggleAnalogMode()

    fun saveScreenshot()
}

object NativeGameCoreApi : GameCoreApi {
    override fun getSaveStateTimes(serial: String): LongArray = NativeLibrary.getSaveStateTimes(serial)

    override fun getSaveStateScreenshot(
        serial: String,
        slot: Int,
    ): ArgbImage? = NativeLibrary.getSaveStateScreenshot(serial, slot)

    override fun saveState(slot: Int): String? = NativeLibrary.saveState(slot)

    override fun loadState(slot: Int): String? = NativeLibrary.loadState(slot)

    override fun savesResumeState(): Boolean = NativeLibrary.getBoolSetting("Main", "SaveStateOnExit", true)

    override fun saveResumeState(): String? = NativeLibrary.saveResumeState()

    override fun getAnalogMode(): Int = NativeLibrary.getAnalogMode()

    override fun isSavingMemoryCards(): Boolean = NativeLibrary.isSavingMemoryCards()

    override fun resetGame() = NativeLibrary.resetGame()

    override fun setFastForward(enabled: Boolean) = NativeLibrary.setFastForward(enabled)

    override fun toggleAnalogMode() = NativeLibrary.toggleAnalogMode()

    override fun saveScreenshot() = NativeLibrary.saveScreenshot()
}
