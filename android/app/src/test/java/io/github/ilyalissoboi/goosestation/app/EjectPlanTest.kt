// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.app.EjectAction.FlushAndStopGame
import io.github.ilyalissoboi.goosestation.app.EjectAction.ShowMessage
import io.github.ilyalissoboi.goosestation.app.EjectAction.StopMirror
import org.junit.Assert.assertEquals
import org.junit.Test

class EjectPlanTest {
    private val card = "9C33-6BBD"
    private val onCard = "content://com.android.externalstorage.documents/tree/9C33-6BBD%3AGames"
    private val internal = "content://com.android.externalstorage.documents/tree/primary%3AGooseStation"
    private val cardGame = "$onCard/Ace Combat 3.chd"
    private val internalGame = "$internal/Games/padtest.cue"
    private val cardData = "content://com.android.externalstorage.documents/tree/9C33-6BBD%3AGooseStation%2Fdata"
    private val internalData = "$internal%2Fdata"

    @Test
    fun aGameOnTheCardIsSavedAndStopped() {
        assertEquals(
            setOf(FlushAndStopGame, ShowMessage),
            EjectPlan.of(card, cardGame, internalData, gameActive = true),
        )
    }

    @Test
    fun aGameAndTheDataFolderBothOnTheCard() {
        assertEquals(
            setOf(FlushAndStopGame, ShowMessage, StopMirror),
            EjectPlan.of(card, cardGame, cardData, gameActive = true),
        )
    }

    @Test
    fun theDataFolderOnTheCardWithAGameElsewhereStopsBoth() {
        assertEquals(
            setOf(FlushAndStopGame, ShowMessage, StopMirror),
            EjectPlan.of(card, internalGame, cardData, gameActive = true),
        )
    }

    @Test
    fun theDataFolderOnTheCardWithNoGameOnlyStopsTheMirror() {
        assertEquals(setOf(StopMirror), EjectPlan.of(card, null, cardData, gameActive = false))
        // The last game's path stays known once it has stopped.
        assertEquals(setOf(StopMirror), EjectPlan.of(card, cardGame, cardData, gameActive = false))
    }

    @Test
    fun neitherOnTheCardDoesNothing() {
        assertEquals(emptySet<EjectAction>(), EjectPlan.of(card, internalGame, internalData, gameActive = true))
        assertEquals(emptySet<EjectAction>(), EjectPlan.of(card, null, null, gameActive = false))
        assertEquals(emptySet<EjectAction>(), EjectPlan.of(card, cardGame, null, gameActive = false))
    }
}
