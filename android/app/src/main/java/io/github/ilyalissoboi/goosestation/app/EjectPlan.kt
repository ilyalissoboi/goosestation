// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.VolumeMatch

/** What [AppController] does when a volume is being ejected. */
enum class EjectAction {
    /** Saves the running game's memory cards to the working copy, then stops the game, closing its disc. */
    FlushAndStopGame,

    /** Stops the mirror's run under way, and every later one until the volume is back. */
    StopMirror,

    /** Says the game was stopped, and that its memory cards are saved on the device. */
    ShowMessage,
}

/** What an eject of a volume does, from what is on it. */
object EjectPlan {
    /**
     * For the volume [volumeId] going away: the game, if one is [gameActive] and either it ([runningGamePath]) or the
     * data folder ([dataTree]) is on the volume, has its memory cards saved and is stopped, and that is said; the
     * mirror stops if the data folder is on it. Nothing, if neither is.
     */
    fun of(
        volumeId: String,
        runningGamePath: String?,
        dataTree: String?,
        gameActive: Boolean,
    ): Set<EjectAction> {
        val gameOnVolume = gameActive && runningGamePath != null && VolumeMatch.treeOnVolume(runningGamePath, volumeId)
        val dataOnVolume = dataTree != null && VolumeMatch.treeOnVolume(dataTree, volumeId)
        return buildSet {
            if (gameActive && (gameOnVolume || dataOnVolume)) {
                add(EjectAction.FlushAndStopGame)
                add(EjectAction.ShowMessage)
            }
            if (dataOnVolume) add(EjectAction.StopMirror)
        }
    }
}
