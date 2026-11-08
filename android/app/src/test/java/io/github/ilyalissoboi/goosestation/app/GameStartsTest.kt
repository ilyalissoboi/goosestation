// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.GameDisc
import io.github.ilyalissoboi.goosestation.core.GameEntry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Starting a game: at once, or after asking whether to resume where it was left. */
class GameStartsTest {
    private val calls = mutableListOf<String>()
    private val api = FakeGameCore(calls)
    private val picture = ArgbImage(1, 1, intArrayOf(-1))
    private val crash = GameEntry("content://games/crash.cue", "Crash Bandicoot", "SCUS-94900", "NTSC-U", 0, 0, null)
    private val exe = GameEntry("content://games/padtest.psexe", "padtest", "", "", 0, 0, null)

    private val records = MemoryRecords()
    private val lastSlots = HashMap<String, Int>()
    private val lastSlotStore =
        object : LastSlotStore {
            override fun get(serial: String): Int? = lastSlots[serial]

            override fun set(
                serial: String,
                slot: Int,
            ) {
                lastSlots[serial] = slot
            }
        }
    private val ac3 =
        GameEntry(
            "content://games/ac3 (disc 1).chd",
            "Ace Combat 3",
            "SLPS-02020",
            "NTSC-J",
            0,
            0,
            null,
            listOf(
                GameDisc("content://games/ac3 (disc 1).chd", "SLPS-02020", "AC3 (Disc 1)", 1),
                GameDisc("content://games/ac3 (disc 2).chd", "SLPS-02021", "AC3 (Disc 2)", 2),
            ),
        )

    private fun TestScope.starts(): GameStarts {
        val starts =
            GameStarts(
                backgroundScope,
                StandardTestDispatcher(testScheduler),
                api,
                records,
                lastSlotStore,
                startFrom = { game, state -> calls += "startFrom ${game.title} $state" },
            ) { game, disc, resume ->
                calls += "start ${game.title} $disc $resume"
            }
        calls.clear()
        return starts
    }

    @Test
    fun noResumeStateStartsAtOnce() =
        runTest {
            val starts = starts()

            starts.play(crash)
            runCurrent()

            assertEquals(listOf("getSaveStateTimes SCUS-94900", "start Crash Bandicoot 1 false"), calls)
            assertNull(starts.offer)
        }

    @Test
    fun aResumeStateIsOffered() =
        runTest {
            api.times[SaveSlots.RESUME] = 1_700_000_000
            api.screenshots[SaveSlots.RESUME] = picture
            val starts = starts()

            starts.play(crash)
            runCurrent()

            val offer = starts.offer!!
            assertSame(crash, offer.game)
            assertEquals(1_700_000_000L, offer.savedAt)
            assertSame(picture, offer.picture)
            assertEquals(listOf("getSaveStateTimes SCUS-94900", "getSaveStatePreview SCUS-94900 -1"), calls)
        }

    @Test
    fun resumeBootsFromIt() =
        runTest {
            api.times[SaveSlots.RESUME] = 1_700_000_000
            val starts = starts()
            starts.play(crash)
            runCurrent()
            calls.clear()

            starts.resume()

            assertEquals(listOf("start Crash Bandicoot 1 true"), calls)
            assertNull(starts.offer)
        }

    @Test
    fun startOverBootsNormally() =
        runTest {
            api.times[SaveSlots.RESUME] = 1_700_000_000
            val starts = starts()
            starts.play(crash)
            runCurrent()
            calls.clear()

            starts.startOver()

            assertEquals(listOf("start Crash Bandicoot 1 false"), calls)
            assertNull(starts.offer)
        }

    @Test
    fun cancelStartsNothing() =
        runTest {
            api.times[SaveSlots.RESUME] = 1_700_000_000
            val starts = starts()
            starts.play(crash)
            runCurrent()
            calls.clear()

            starts.cancel()

            assertEquals(emptyList<String>(), calls)
            assertNull(starts.offer)
        }

    @Test
    fun aGameWithoutASerialStartsAtOnce() =
        runTest {
            val starts = starts()

            starts.play(exe)

            assertEquals(listOf("start padtest 1 false"), calls)
        }

    @Test
    fun aGameStartsOnItsLastDisc() =
        runTest {
            records.setLastDisc("SCUS-94900", 2)
            val starts = starts()

            starts.play(crash)
            runCurrent()

            assertEquals("start Crash Bandicoot 2 false", calls.last())
        }

    @Test
    fun aResumeKeepsTheLastDisc() =
        runTest {
            records.setLastDisc("SCUS-94900", 2)
            api.times[SaveSlots.RESUME] = 1_700_000_000
            val starts = starts()
            starts.play(crash)
            runCurrent()
            calls.clear()

            starts.resume()

            assertEquals(listOf("start Crash Bandicoot 2 true"), calls)
        }

    @Test
    fun aSecondPlayWhileCheckingIsIgnored() =
        runTest {
            val starts = starts()

            starts.play(crash)
            starts.play(crash)
            runCurrent()

            assertEquals(1, calls.count { it.startsWith("start") })
        }

    @Test
    fun aPlayWhileOfferingIsIgnored() =
        runTest {
            api.times[SaveSlots.RESUME] = 1_700_000_000
            val starts = starts()
            starts.play(crash)
            runCurrent()

            starts.play(exe)

            assertEquals(0, calls.count { it.startsWith("start") })
            assertSame(crash, starts.offer!!.game)
        }

    @Test
    fun answersWithoutAnOfferDoNothing() =
        runTest {
            val starts = starts()

            starts.resume()
            starts.startOver()
            starts.cancel()

            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun aMultiDiscGameMigratesOnceBeforeTheResumeCheck() =
        runTest {
            api.serialTimes["SLPS-02020"] = mapOf(3 to 100L)
            api.serialTimes["SLPS-02021"] = mapOf(3 to 200L, SaveSlots.RESUME to 300L)
            lastSlots["SLPS-02021"] = 3
            val starts = starts()

            starts.play(ac3)
            runCurrent()

            val moves = calls.filter { it.startsWith("moveSaveState") }
            assertEquals(
                listOf(
                    "moveSaveState SLPS-02020 3 SLPS-02020 1",
                    "moveSaveState SLPS-02021 -1 SLPS-02020 -1",
                    "moveSaveState SLPS-02021 3 SLPS-02020 3",
                ),
                moves,
            )
            // The moves come before the resume check, which reads the key's times last.
            assertTrue(calls.indexOf(moves.last()) < calls.lastIndexOf("getSaveStateTimes SLPS-02020"))
            assertTrue(records.migrated("SLPS-02020"))
            assertEquals(3, lastSlots["SLPS-02020"])

            starts.cancel()
            calls.clear()
            starts.play(ac3)
            runCurrent()
            assertEquals(0, calls.count { it.startsWith("moveSaveState") })
        }

    @Test
    fun aFailedMoveIsTriedAgainNextTime() =
        runTest {
            api.serialTimes["SLPS-02020"] = mapOf(3 to 100L)
            api.serialTimes["SLPS-02021"] = mapOf(3 to 200L)
            api.moveFails += "moveSaveState SLPS-02020 3 SLPS-02020 1"
            val starts = starts()

            starts.play(ac3)
            runCurrent()

            assertEquals(
                listOf("moveSaveState SLPS-02020 3 SLPS-02020 1"),
                calls.filter { it.startsWith("moveSaveState") },
            )
            assertFalse(records.migrated("SLPS-02020"))
            assertEquals(1, calls.count { it.startsWith("start") })
        }

    @Test
    fun aSingleDiscGameNeverMigrates() =
        runTest {
            val starts = starts()

            starts.play(crash.copy(discs = listOf(GameDisc(crash.path, crash.serial, crash.title, 1))))
            runCurrent()

            assertEquals(listOf("getSaveStateTimes SCUS-94900", "start Crash Bandicoot 1 false"), calls)
            assertFalse(records.migrated("SCUS-94900"))
        }

    // A boot with the resume state would turn hardcore off (the core's ConfirmMessage answers yes on Android): with
    // hardcore about to start, the game starts from the beginning, and its resume state is kept.
    @Test
    fun hardcoreOffersNoResume() =
        runTest {
            api.times[SaveSlots.RESUME] = 1_700_000_000
            api.hardcoreWouldStart = true
            val starts = starts()
            starts.play(crash)
            runCurrent()
            assertTrue(starts.offer!!.hardcore)
            calls.clear()

            starts.resume()
            assertEquals(emptyList<String>(), calls)
            starts.startOver()
            assertEquals(listOf("start Crash Bandicoot 1 false"), calls)
        }

    @Test
    fun withoutHardcoreTheOfferIsAsBefore() =
        runTest {
            api.times[SaveSlots.RESUME] = 1_700_000_000
            val starts = starts()

            starts.play(crash)
            runCurrent()

            assertFalse(starts.offer!!.hardcore)
        }

    // --- A start from a save ---

    @Test
    fun playFromStateStartsFromIt() =
        runTest {
            val starts = starts()

            starts.playFromState(crash, StateRef("", 3, shared = true)) { calls += "refused $it" }
            runCurrent()

            assertEquals(listOf("startFrom Crash Bandicoot ${StateRef("", 3, shared = true)}"), calls)
        }

    @Test
    fun playFromStateIsRefusedInHardcore() =
        runTest {
            api.hardcoreWouldStart = true
            val starts = starts()

            starts.playFromState(crash, StateRef("SCUS-94900", 2)) { calls += "refused $it" }
            runCurrent()

            assertEquals(listOf("refused Loading a state is off in hardcore mode"), calls)
        }
}
