// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.AchievementSection
import io.github.ilyalissoboi.goosestation.core.GameAchievements
import io.github.ilyalissoboi.goosestation.core.Leaderboard
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import io.github.ilyalissoboi.goosestation.core.PatchCodes
import io.github.ilyalissoboi.goosestation.core.SaveStatePreview

/** A game's save state slots, as the core numbers them. */
object SaveSlots {
    /** The resume state's slot, saved by Exit game. */
    const val RESUME = -1

    /** Slots 1 to [COUNT] per game. */
    const val COUNT = 10
}

/**
 * The core's calls for the running game and its save states, as [NativeLibrary] has them ([NativeGameCoreApi]), so
 * the in-game menu can be tested with a fake. Every call is made on `AppController.gameDispatcher`: all but the last
 * four block until the core has run them. Save states are kept under the game's key ([GameEntry.serial]).
 */
interface GameCoreApi {
    /** When each of [serial]'s states was saved, in Unix seconds, 0 for none: `[0]` the resume state, `[n]` slot n. */
    fun getSaveStateTimes(serial: String): LongArray

    /** The picture in [serial]'s state in [slot], and the running game's disc it was made on (0 if unknown). */
    fun getSaveStatePreview(
        serial: String,
        slot: Int,
    ): SaveStatePreview?

    /** Saves to [key]'s [slot]: null when it's saved, or else why not. */
    fun saveState(
        key: String,
        slot: Int,
    ): String?

    /** Loads [key]'s [slot] (another disc's serial for its other saves): null when it's loaded, or else why not. */
    fun loadState(
        key: String,
        slot: Int,
    ): String?

    /** Undoes the last load, as the core keeps it: false when there's none. */
    fun undoLoadState(): Boolean

    /** The running game's patch codes. */
    fun getPatchCodes(): PatchCodes

    /** Switches code [index] on or off, for now and next time: null when it's done, or else why not. */
    fun setPatchCode(
        index: Int,
        on: Boolean,
    ): String?

    /** Applies manual code [index] once: null when it's done, or else why not. */
    fun applyPatchCode(index: Int): String?

    /** The number of the running game's disc in the drive, 0 if unknown. */
    fun getCurrentDisc(): Int

    /** Puts the running game's disc [number] in the drive: null when it's in, or else why not. */
    fun changeDisc(number: Int): String?

    /** Moves a state to another serial's slot, onto nothing only: false if it didn't move. */
    fun moveSaveState(
        fromSerial: String,
        fromSlot: Int,
        toSerial: String,
        toSlot: Int,
    ): Boolean

    /** Whether Exit game saves a resume state: `[Main] SaveStateOnExit`. */
    fun savesResumeState(): Boolean

    /** Saves the resume state under [key] for Exit game: null when it's saved or there's none to save, else why not. */
    fun saveResumeState(key: String): String?

    /** -1 if Port 1 isn't a DualShock, 0 digital, 1 analog. */
    fun getAnalogMode(): Int

    /** The game is writing to a memory card: a reset or an exit now can damage that save. */
    fun isSavingMemoryCards(): Boolean

    fun resetGame()

    fun setFastForward(enabled: Boolean)

    fun toggleAnalogMode()

    fun saveScreenshot()

    /** Hardcore mode is active now: no state loads, patch codes or fast forward. */
    fun isHardcoreActive(): Boolean

    /** A game started now would start in hardcore mode: it's switched on, and a sign-in is stored. */
    fun hardcoreWouldStart(): Boolean

    /** The running game's RetroAchievements: its status and counts. */
    fun getGameAchievements(): GameAchievements

    /** The running game's achievements, in rcheevos's sections by progress. */
    fun getAchievementSections(): List<AchievementSection>

    /** The running game's leaderboards, in the set's order. */
    fun getLeaderboards(): List<Leaderboard>

    /** Starts fetching leaderboard [id]'s entries; the answer comes to [NativeCallbacks.onLeaderboard] as [request]. */
    fun fetchLeaderboard(
        id: Int,
        request: Int,
    )
}

object NativeGameCoreApi : GameCoreApi {
    override fun getSaveStateTimes(serial: String): LongArray = NativeLibrary.getSaveStateTimes(serial)

    override fun getSaveStatePreview(
        serial: String,
        slot: Int,
    ): SaveStatePreview? = NativeLibrary.getSaveStatePreview(serial, slot)

    override fun saveState(
        key: String,
        slot: Int,
    ): String? = NativeLibrary.saveState(key, slot)

    override fun loadState(
        key: String,
        slot: Int,
    ): String? = NativeLibrary.loadState(key, slot)

    override fun undoLoadState(): Boolean = NativeLibrary.undoLoadState()

    override fun getPatchCodes(): PatchCodes = NativeLibrary.getPatchCodes() ?: PatchCodes("", emptyArray())

    override fun setPatchCode(
        index: Int,
        on: Boolean,
    ): String? = NativeLibrary.setPatchCode(index, on)

    override fun applyPatchCode(index: Int): String? = NativeLibrary.applyPatchCode(index)

    override fun getCurrentDisc(): Int = NativeLibrary.getCurrentDisc()

    override fun changeDisc(number: Int): String? = NativeLibrary.changeDisc(number)

    override fun moveSaveState(
        fromSerial: String,
        fromSlot: Int,
        toSerial: String,
        toSlot: Int,
    ): Boolean = NativeLibrary.moveSaveState(fromSerial, fromSlot, toSerial, toSlot)

    override fun savesResumeState(): Boolean = NativeLibrary.getBoolSetting("Main", "SaveStateOnExit", true)

    override fun saveResumeState(key: String): String? = NativeLibrary.saveResumeState(key)

    override fun getAnalogMode(): Int = NativeLibrary.getAnalogMode()

    override fun isSavingMemoryCards(): Boolean = NativeLibrary.isSavingMemoryCards()

    override fun resetGame() = NativeLibrary.resetGame()

    override fun setFastForward(enabled: Boolean) = NativeLibrary.setFastForward(enabled)

    override fun toggleAnalogMode() = NativeLibrary.toggleAnalogMode()

    override fun saveScreenshot() = NativeLibrary.saveScreenshot()

    override fun isHardcoreActive(): Boolean = NativeLibrary.isHardcoreActive()

    override fun hardcoreWouldStart(): Boolean = NativeLibrary.hardcoreWouldStart()

    override fun getGameAchievements(): GameAchievements = NativeLibrary.getGameAchievements()

    override fun getAchievementSections(): List<AchievementSection> = NativeLibrary.getAchievementSections().toList()

    override fun getLeaderboards(): List<Leaderboard> = NativeLibrary.getLeaderboards().toList()

    override fun fetchLeaderboard(
        id: Int,
        request: Int,
    ) = NativeLibrary.fetchLeaderboard(id, request)
}
