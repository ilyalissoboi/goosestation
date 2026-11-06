// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.MemoryCoreSettings
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A game's settings pages: their summary, reads, saves, Copy and Clear, with a fake core and files in memory. */
class GameSettingsPagesTest {
    private val calls = mutableListOf<String>()
    private val api = FakeCoreApi(calls)
    private val global = MemoryCoreSettings().apply { setInt("GPU", "ResolutionScale", 3) }
    private val game = GameEntry("/games/padtest.psexe", "Pad Test", KEY, "NTSC-U", 0, 0, null)
    private val file get() = api.gameLayer(KEY)

    private fun TestScope.pages(running: Boolean = false) =
        GameSettingsPages(backgroundScope, StandardTestDispatcher(testScheduler), api, global) { api.gameLayer(it) }
            .also {
                it.open(game, running)
                runCurrent()
                calls.clear()
            }

    private fun TestScope.shownGraphics(pages: GameSettingsPages) =
        pages.also {
            it.openPage(GamePage.Graphics)
            runCurrent()
            calls.clear()
        }

    @Test
    fun openReadsTheSummary() =
        runTest {
            file.setInt("GPU", "ResolutionScale", 4)
            file.setString("Console", "Region", "NTSC-J")
            file.setString("Desktop", "Only", "1")
            file.setBool("ControllerPorts", "UseGameSettingsForController", true)

            val summary = pages().summary!!

            assertEquals(1, summary.counts[GamePage.Graphics])
            assertEquals(1, summary.counts[GamePage.Console])
            assertEquals(2, summary.total)
            assertEquals(1, summary.others)
        }

    // A chain is one row of the page's, whatever keys it has: on/off, the count, each stage's shader and options.
    @Test
    fun aChainCountsOnceOnItsPage() =
        runTest {
            file.setBool("PostProcessing", "Enabled", true)
            file.setInt("PostProcessing", "StageCount", 1)
            file.setString("PostProcessing/Stage1", "ShaderName", "crt-lottes")
            file.setString("PostProcessing/Stage1", "warpX", "0.05")
            file.setInt("InternalPostProcessing", "StageCount", 1)
            file.setString("InternalPostProcessing/Stage1", "ShaderName", "simple-flip")

            val summary = pages().summary!!

            assertEquals(2, summary.counts[GamePage.PostProcessing])
            assertEquals(0, summary.others)
        }

    // Its logging rows stay global on a game's page: a game's own log level is one the app doesn't show.
    @Test
    fun aGamesAdvancedCountsItsRows() =
        runTest {
            file.setBool("CDROM", "RegionCheck", true)
            file.setString("CPU", "FastmemMode", "LUT")
            file.setString("Logging", "LogLevel", "Dev")

            val summary = pages().summary!!

            assertEquals(2, summary.counts[GamePage.Advanced])
            assertEquals(1, summary.others)
        }

    @Test
    fun aPageReadsTheGamesValuesOverTheGlobalOnes() =
        runTest {
            file.setInt("GPU", "ResolutionScale", 4)

            val values = shownGraphics(pages()).values(GamePage.Graphics)!!

            assertTrue(values.isGame)
            assertEquals(4, values[GraphicsSettings.RESOLUTION_SCALE])
            assertEquals(3, values.global(GraphicsSettings.RESOLUTION_SCALE))
        }

    @Test
    fun aChangeSavesToTheGamesFileOnly() =
        runTest {
            val pages = shownGraphics(pages())
            val before = global.values.toMap()

            pages.change(
                GamePage.Graphics,
                pages.values(GamePage.Graphics)!!.with(GraphicsSettings.RESOLUTION_SCALE, 5),
            )
            runCurrent()

            assertEquals("5", file.values["GPU/ResolutionScale"])
            assertEquals(before, global.values)
            assertFalse("applySettings" in calls)
            assertEquals(1, pages.summary!!.counts[GamePage.Graphics])
        }

    @Test
    fun aRunningGamesChangeIsApplied() =
        runTest {
            val pages = shownGraphics(pages(running = true))

            pages.change(
                GamePage.Graphics,
                pages.values(GamePage.Graphics)!!.with(GraphicsSettings.RESOLUTION_SCALE, 5),
            )
            runCurrent()

            assertEquals(listOf("applyGameSettings $KEY"), calls)
        }

    @Test
    fun aLibraryChangeAppliesNothing() =
        runTest {
            val pages = shownGraphics(pages())

            pages.change(
                GamePage.Graphics,
                pages.values(GamePage.Graphics)!!.with(GraphicsSettings.RESOLUTION_SCALE, 5),
            )
            runCurrent()

            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun useGlobalOnTheLastRowEmptiesTheSummary() =
        runTest {
            file.setInt("GPU", "ResolutionScale", 4)
            val pages = shownGraphics(pages())

            pages.change(
                GamePage.Graphics,
                pages.values(GamePage.Graphics)!!.withGlobal(GraphicsSettings.RESOLUTION_SCALE),
            )
            runCurrent()

            assertTrue(file.values.isEmpty())
            assertEquals(0, pages.summary!!.total)
            assertEquals(3, pages.values(GamePage.Graphics)!![GraphicsSettings.RESOLUTION_SCALE])
        }

    @Test
    fun copyAsksFirst() =
        runTest {
            api.copied = mapOf(("GPU" to "ResolutionScale") to "3")
            val pages = pages()

            pages.ask(GameQuestion.Copy)
            runCurrent()
            assertEquals(GameQuestion.Copy, pages.asking)
            assertEquals(emptyList<String>(), calls)

            pages.confirm()
            runCurrent()

            assertNull(pages.asking)
            assertEquals(listOf("copyGlobalSettingsToGame $KEY"), calls)
            assertEquals(1, pages.summary!!.counts[GamePage.Graphics])
        }

    @Test
    fun aQuestionCanBeCancelled() =
        runTest {
            val pages = pages()

            pages.ask(GameQuestion.Clear)
            pages.cancelQuestion()
            runCurrent()

            assertNull(pages.asking)
            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun clearDeletesAndRereads() =
        runTest {
            file.setInt("GPU", "ResolutionScale", 4)
            val pages = shownGraphics(pages(running = true))

            pages.ask(GameQuestion.Clear)
            pages.confirm()
            runCurrent()

            assertEquals(listOf("clearGameSettings $KEY", "applyGameSettings $KEY"), calls)
            assertEquals(3, pages.values(GamePage.Graphics)!![GraphicsSettings.RESOLUTION_SCALE])
            assertEquals(0, pages.summary!!.total)
        }

    @Test
    fun aPullReadsTheShownPageAgain() =
        runTest {
            val pages = shownGraphics(pages())
            file.setInt("GPU", "ResolutionScale", 6)

            pages.reloadShown()
            // The old values stay shown meanwhile, so the page keeps its focus.
            assertEquals(3, pages.values(GamePage.Graphics)!![GraphicsSettings.RESOLUTION_SCALE])
            runCurrent()

            assertEquals(6, pages.values(GamePage.Graphics)!![GraphicsSettings.RESOLUTION_SCALE])
            assertEquals(1, pages.summary!!.total)
        }

    // A read whose values were taken before the player's change mustn't show the old value again.
    @Test
    fun aReadOvertakenByAChangeIsDropped() =
        runTest {
            // Something of the game's own, so the reload reads the file, and the change comes in while it does.
            file.setInt("GPU", "ResolutionScale", 4)
            val pages = shownGraphics(pages())

            file.duringRead = {
                pages.change(
                    GamePage.Graphics,
                    pages.values(GamePage.Graphics)!!.with(GraphicsSettings.RESOLUTION_SCALE, 7),
                )
            }
            pages.reloadShown()
            runCurrent()

            assertEquals(7, pages.values(GamePage.Graphics)!![GraphicsSettings.RESOLUTION_SCALE])
            assertTrue(pages.values(GamePage.Graphics)!!.isSet(GraphicsSettings.RESOLUTION_SCALE))
        }

    @Test
    fun closeForgetsTheGame() =
        runTest {
            val pages = shownGraphics(pages())

            pages.closePage()
            assertNull(pages.page)
            assertEquals(GamePage.Graphics, pages.cameFrom)
            pages.close()

            assertNull(pages.game)
            assertNull(pages.values(GamePage.Graphics))
        }

    // --- Controllers ---

    @Test
    fun controllerKeysCountOnlyWithTheFlag() =
        runTest {
            file.setString("Pad1", "Type", "NeGcon")
            file.setString("ControllerPorts", "MultitapMode", "Port1Only")
            assertEquals(0, pages().summary!!.counts[GamePage.Controllers])

            file.setBool("ControllerPorts", "UseGameSettingsForController", true)
            val summary = pages().summary!!

            assertEquals(2, summary.counts[GamePage.Controllers])
            assertEquals(0, summary.others)
        }

    @Test
    fun aGamesBindingsAreOthersNotItsControllers() =
        runTest {
            file.setBool("ControllerPorts", "UseGameSettingsForController", true)
            file.setString("Pad1", "Cross", "SDL-0/A")

            val summary = pages().summary!!

            assertEquals(0, summary.counts[GamePage.Controllers])
            assertEquals(1, summary.others)
        }

    @Test
    fun theControllersPageOffersTheGamesMultitapsPorts() =
        runTest {
            file.setBool("ControllerPorts", "UseGameSettingsForController", true)
            file.setString("ControllerPorts", "MultitapMode", "Port1Only")
            val pages = pages()

            pages.openPage(GamePage.Controllers)
            runCurrent()

            assertEquals(listOf(0, 2, 3, 4, 1), pages.controllers!!.pads)
            assertTrue(pages.values(GamePage.Controllers)!!.isSet(PortSettings.MULTITAP))
        }

    @Test
    fun withoutTheFlagTheControllersFollowGlobal() =
        runTest {
            file.setString("ControllerPorts", "MultitapMode", "Port1Only")
            val pages = pages()

            pages.openPage(GamePage.Controllers)
            runCurrent()

            assertEquals(listOf(0, 1), pages.controllers!!.pads)
            assertFalse(pages.values(GamePage.Controllers)!!.isSet(PortSettings.MULTITAP))
        }

    @Test
    fun aControllersChangeUpdatesTheFlagAndDefaults() =
        runTest {
            val pages = pages(running = true)
            pages.openPage(GamePage.Controllers)
            runCurrent()
            calls.clear()

            pages.change(
                GamePage.Controllers,
                pages.values(GamePage.Controllers)!!.with(PortSettings.MULTITAP, "Port1Only"),
            )
            runCurrent()

            assertEquals(listOf("updateGameControllers $KEY", "applyGameSettings $KEY"), calls.filter { "Game" in it })
            assertEquals(listOf(0, 2, 3, 4, 1), pages.controllers!!.pads)
        }

    @Test
    fun anInputProfileIsReported() =
        runTest {
            api.inputProfiles = mapOf(KEY to "Desk")

            assertEquals("Desk", pages().summary!!.inputProfile)
        }

    private companion object {
        const val KEY = "SLUS-00001"
    }
}
