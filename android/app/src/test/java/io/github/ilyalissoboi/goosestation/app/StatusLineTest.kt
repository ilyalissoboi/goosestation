// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status line: each owner's line is its own to clear, and work in progress doesn't hide an error. */
class StatusLineTest {
    private val line = StatusLine()

    @Test
    fun startsWithTheLineGiven() {
        val starting = StatusLine(Status.Starting, "Starting…")

        assertEquals("Starting…", starting.text)
        assertTrue(starting.shows(Status.Starting))
        assertFalse(starting.isError)
    }

    @Test
    fun anOwnerClearsItsOwnLine() {
        line.progress(Status.Scanning, "Scanning your games…")

        line.clear(Status.Scanning)

        assertNull(line.text)
    }

    @Test
    fun anOwnerDoesntClearAnothersLine() {
        line.show(Status.Note, "Crash Bandicoot didn't start.")

        line.clear(Status.Scanning)
        line.clear(Status.LookingAtFolder)

        assertEquals("Crash Bandicoot didn't start.", line.text)
    }

    @Test
    fun showsSaysWhoseLineItIsNotItsWords() {
        line.show(Status.Note, "Copying your data from the data folder…")

        assertFalse(line.shows(Status.Copying))
        assertTrue(line.shows(Status.Note))
    }

    @Test
    fun nothingShowsOnceCleared() {
        line.show(Status.Copying, "Copying…")
        line.clear()

        assertFalse(line.shows(Status.Copying))
        assertNull(line.text)
    }

    @Test
    fun workInProgressDoesntHideAnError() {
        line.error("The core failed to start.")

        line.progress(Status.Scanning, "Scanning your games…")

        assertEquals("The core failed to start.", line.text)
        assertTrue(line.isError)
        assertTrue(line.shows(Status.Error))
    }

    @Test
    fun aNoteOrAnotherErrorReplacesAnError() {
        line.error("The core failed to start.")
        line.show(Status.Note, "That's your data folder already.")

        assertEquals("That's your data folder already.", line.text)
        assertFalse(line.isError)

        line.error("No PS1 BIOS found in this folder.")
        line.error("Couldn't read the folder.")

        assertEquals("Couldn't read the folder.", line.text)
        assertTrue(line.isError)
    }

    @Test
    fun clearingAnErrorLeavesNoError() {
        line.error("The core failed to start.")

        line.clear()

        assertNull(line.text)
        assertFalse(line.isError)
        line.progress(Status.Stopping, "Stopping the game…")
        assertEquals("Stopping the game…", line.text)
    }

    @Test
    fun theProgressLineIsNotThePlainCopyingLine() {
        line.progress(Status.CopyingProgress, "Copying… (3 of 9)")

        assertFalse(line.shows(Status.Copying))
    }
}
