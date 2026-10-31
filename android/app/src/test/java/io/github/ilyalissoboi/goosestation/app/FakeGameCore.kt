// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.ArgbImage

/**
 * A core for the in-game menu's tests: [times] and [screenshots] by slot ([SaveSlots.RESUME] for the resume state),
 * [saveError], [loadError] and [analog] as the calls' results, and every call logged to [calls] as "saveState 3" and so on.
 */
class FakeGameCore(
    private val calls: MutableList<String>,
) : GameCoreApi {
    val times = HashMap<Int, Long>()
    val screenshots = HashMap<Int, ArgbImage>()
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

    override fun getSaveStateScreenshot(
        serial: String,
        slot: Int,
    ): ArgbImage? {
        calls += "getSaveStateScreenshot $serial $slot"
        return screenshots[slot]
    }

    override fun saveState(slot: Int): String? {
        calls += "saveState $slot"
        return saveError
    }

    override fun loadState(slot: Int): String? {
        calls += "loadState $slot"
        return loadError
    }

    override fun savesResumeState(): Boolean {
        calls += "savesResumeState"
        return resumeOn
    }

    override fun saveResumeState(): String? {
        calls += "saveResumeState"
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
