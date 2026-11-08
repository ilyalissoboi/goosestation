// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.GameDisc
import io.github.ilyalissoboi.goosestation.core.SaveStatePreview
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The save lists as read from a fake core. */
class SaveReaderTest {
    private val calls = mutableListOf<String>()
    private val api = FakeGameCore(calls)
    private val picture = ArgbImage(2, 1, intArrayOf(-1, -1))

    @Test
    fun sharedPreviewsSayTheGameTheyHold() =
        runTest {
            val reader = SaveReader(api, StandardTestDispatcher(testScheduler))
            api.sharedTimes[3] = 30
            api.sharedHolds[3] = SaveStatePreview(picture, 2, "SCUS-94900", "CRASH", "/g/crash.chd", "Crash Bandicoot")

            val entries = reader.shared(SlotMode.Load)
            assertEquals(listOf(SlotEntry(3, 30L, shared = true)), entries)
            val read = reader.preview(entries.single(), "KEY", "/g/this.chd", multiDisc = false)!!
            assertSame(picture, read.picture)
            assertEquals(HeldGame("Crash Bandicoot", "SCUS-94900", "/g/crash.chd"), read.holds)
            // A shared slot's disc is the game it holds': it's shown, whatever the running game.
            assertEquals(2, read.disc)
            assertEquals(listOf("getSharedStateTimes", "getStatePreview shared 3 /g/this.chd"), calls)
        }

    @Test
    fun ownAndOtherDiscsSaves() =
        runTest {
            val reader = SaveReader(api, StandardTestDispatcher(testScheduler))
            api.times[2] = 20
            api.serialTimes["SLPS-02021"] = mapOf(5 to 50L)
            api.stateDiscs[2] = 1

            assertEquals(listOf(SlotEntry(2, 20L)), reader.own("SLPS-02020", SlotMode.Load))
            val discs = listOf(GameDisc("a", "SLPS-02020", "AC3 1", 1), GameDisc("b", "SLPS-02021", "AC3 2", 2))
            assertEquals(listOf(SlotEntry(5, 50L, owner = "SLPS-02021", disc = 2)), reader.others("SLPS-02020", discs))

            val own = reader.preview(SlotEntry(2, 20L), "SLPS-02020", "/g/ac3.m3u", multiDisc = true)!!
            assertEquals(1, own.disc)
            assertNull(reader.preview(SlotEntry(7, 70L), "SLPS-02020", "/g/ac3.m3u", multiDisc = true))
        }
}
