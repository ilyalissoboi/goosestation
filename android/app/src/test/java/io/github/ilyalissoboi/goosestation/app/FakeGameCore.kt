// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.SaveStatePreview

/**
 * A core for the in-game menu's tests: [times], [screenshots] and [stateDiscs] by slot ([SaveSlots.RESUME] for the
 * resume state), [moveFails] for the moves that fail,
 * [saveError], [loadError] and [analog] as the calls' results, and every call logged to [calls] as "saveState 3" and so on.
 */
class FakeGameCore(
    private val calls: MutableList<String>,
) : GameCoreApi {
    val times = HashMap<Int, Long>()
    val screenshots = HashMap<Int, ArgbImage>()
    val stateDiscs = HashMap<Int, Int>()
    var saveError: String? = null
    var loadError: String? = null
    var resumeError: String? = null
    var cardBusy = false
    var resumeOn = true
    var analog = -1

    override fun getSaveStateTimes(serial: String): LongArray {
        calls += "getSaveStateTimes $serial"
        return LongArray(SaveSlots.COUNT + 1) { index -> times[if (index == 0) SaveSlots.RESUME else index] ?: 0L }
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
        calls += "moveSaveState $fromSerial $fromSlot $toSerial $toSlot"
        return true
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
}
