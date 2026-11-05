// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.AchievementSection
import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.GameAchievements
import io.github.ilyalissoboi.goosestation.core.Leaderboard
import io.github.ilyalissoboi.goosestation.core.PatchCode
import io.github.ilyalissoboi.goosestation.core.PatchCodes
import io.github.ilyalissoboi.goosestation.core.SaveStatePreview

/**
 * A core for the in-game menu's tests: [times], [screenshots] and [stateDiscs] by slot ([SaveSlots.RESUME] for the
 * resume state), [serialTimes] for a serial whose times differ from [times], [moveFails] for the moves that fail,
 * [saveError], [loadError] and [analog] as the calls' results, and every call logged to [calls] as "saveState 3" and so on.
 */
class FakeGameCore(
    private val calls: MutableList<String>,
) : GameCoreApi {
    val times = HashMap<Int, Long>()
    val screenshots = HashMap<Int, ArgbImage>()
    val stateDiscs = HashMap<Int, Int>()
    val serialTimes = HashMap<String, Map<Int, Long>>()
    val moveFails = HashSet<String>()
    var discInDrive = 1
    var discError: String? = null
    var codes = listOf<PatchCode>()
    var codesFile = "Crash Bandicoot.cht"
    var codeError: String? = null
    var saveError: String? = null
    var loadError: String? = null
    var resumeError: String? = null
    var cardBusy = false
    var resumeOn = true
    var analog = -1

    /** [undoLoadState]'s result. */
    var undoes = true

    /** [isHardcoreActive] and [hardcoreWouldStart]: queries, so not logged. */
    var hardcore = false
    var hardcoreWouldStart = false

    override fun isHardcoreActive(): Boolean = hardcore

    override fun hardcoreWouldStart(): Boolean = hardcoreWouldStart

    /** [getGameAchievements]'s answer (a query, so not logged), and [getAchievementSections]'s. */
    var achievementsOfGame = GameAchievements(GameAchievements.OFF, 0, 0, 0, 0, false, false, false)
    var sections = listOf<AchievementSection>()

    override fun getGameAchievements(): GameAchievements = achievementsOfGame

    var boards = listOf<Leaderboard>()

    override fun getLeaderboards(): List<Leaderboard> {
        calls += "getLeaderboards"
        return boards
    }

    override fun fetchLeaderboard(
        id: Int,
        request: Int,
    ) {
        calls += "fetchLeaderboard $id $request"
    }

    override fun getAchievementSections(): List<AchievementSection> {
        calls += "getAchievementSections"
        return sections
    }

    override fun getSaveStateTimes(serial: String): LongArray {
        calls += "getSaveStateTimes $serial"
        val saved = serialTimes[serial] ?: times
        return LongArray(SaveSlots.COUNT + 1) { index -> saved[if (index == 0) SaveSlots.RESUME else index] ?: 0L }
    }

    override fun getSaveStatePreview(
        serial: String,
        slot: Int,
    ): SaveStatePreview? {
        calls += "getSaveStatePreview $serial $slot"
        val picture = screenshots[slot]
        val disc = stateDiscs[slot] ?: 0
        return if (picture == null && disc == 0) null else SaveStatePreview(picture, disc)
    }

    override fun undoLoadState(): Boolean {
        calls += "undoLoadState"
        return undoes
    }

    override fun saveState(
        key: String,
        slot: Int,
    ): String? {
        calls += "saveState $key $slot"
        return saveError
    }

    override fun loadState(
        key: String,
        slot: Int,
    ): String? {
        calls += "loadState $key $slot"
        return loadError
    }

    override fun moveSaveState(
        fromSerial: String,
        fromSlot: Int,
        toSerial: String,
        toSlot: Int,
    ): Boolean {
        val move = "moveSaveState $fromSerial $fromSlot $toSerial $toSlot"
        calls += move
        return move !in moveFails
    }

    override fun savesResumeState(): Boolean {
        calls += "savesResumeState"
        return resumeOn
    }

    override fun saveResumeState(key: String): String? {
        calls += "saveResumeState $key"
        return resumeError
    }

    override fun isSavingMemoryCards(): Boolean {
        calls += "isSavingMemoryCards"
        return cardBusy
    }

    override fun getAnalogMode(): Int {
        calls += "getAnalogMode"
        return analog
    }

    override fun resetGame() {
        calls += "resetGame"
    }

    override fun setFastForward(enabled: Boolean) {
        calls += "setFastForward $enabled"
    }

    override fun toggleAnalogMode() {
        calls += "toggleAnalogMode"
    }

    override fun saveScreenshot() {
        calls += "saveScreenshot"
    }

    override fun getCurrentDisc(): Int {
        calls += "getCurrentDisc"
        return discInDrive
    }

    override fun changeDisc(number: Int): String? {
        calls += "changeDisc $number"
        if (discError == null) discInDrive = number
        return discError
    }

    override fun getPatchCodes(): PatchCodes {
        calls += "getPatchCodes"
        return PatchCodes(codesFile, codes.toTypedArray())
    }

    override fun setPatchCode(
        index: Int,
        on: Boolean,
    ): String? {
        calls += "setPatchCode $index $on"
        return codeError
    }

    override fun applyPatchCode(index: Int): String? {
        calls += "applyPatchCode $index"
        return codeError
    }
}
