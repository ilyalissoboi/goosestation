// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The search field's keyboard closing is told once for each opening, in whatever order the keyboard's events land. */
class KeyboardTrackerTest {
    private var closes = 0
    private val tracker = KeyboardTracker { closes++ }

    @Test
    fun backClosesOnce() {
        tracker.requested()
        tracker.shown()
        tracker.hidden()

        assertEquals(1, closes)
    }

    @Test
    fun searchClosesOnceThoughTheKeyboardHidesAfterIt() {
        tracker.requested()
        tracker.shown()
        tracker.searched()
        tracker.hidden()

        assertEquals(1, closes)
    }

    // What SearchFieldTest.theSearchActionClosesTheKeyboard hit on a busy emulator: the action came before the keyboard
    // asked for was on screen, so it showed, and hid, after its close was told.
    @Test
    fun searchBeforeTheKeyboardShowsClosesOnce() {
        tracker.requested()
        tracker.searched()
        tracker.shown()
        tracker.hidden()

        assertEquals(1, closes)
    }

    @Test
    fun searchWithNoKeyboardSeenIsAClose() {
        tracker.searched()

        assertEquals(1, closes)
    }

    @Test
    fun aKeyboardNeverOpenedDoesntClose() {
        tracker.hidden()

        assertEquals(0, closes)
    }

    @Test
    fun eachOpeningClosesOnce() {
        tracker.requested()
        tracker.shown()
        tracker.searched()
        tracker.hidden()
        tracker.requested()
        tracker.shown()
        tracker.hidden()

        assertEquals(2, closes)
    }

    // The keyboard asked for never showed (Search cancelled it). The next one asked for is a new opening.
    @Test
    fun anOpeningAfterASearchThatCancelledTheKeyboardCloses() {
        tracker.requested()
        tracker.searched()
        tracker.requested()
        tracker.shown()
        tracker.hidden()

        assertEquals(2, closes)
    }

    // A keyboard the field didn't ask for (the system's, on a tap) still closes once.
    @Test
    fun aKeyboardNotAskedForClosesOnce() {
        tracker.shown()
        tracker.hidden()

        assertEquals(1, closes)
    }

    // The focus left before the keyboard asked for showed: one that shows later is its own opening.
    @Test
    fun aKeyboardShownAfterTheFocusLeftIsItsOwnOpening() {
        tracker.requested()
        tracker.searched()
        tracker.focusLost()
        tracker.shown()
        tracker.hidden()

        assertEquals(2, closes)
    }
}
