// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.settings.BiosRegion
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.MemoryCoreSettings
import io.github.ilyalissoboi.goosestation.settings.NameList
import io.github.ilyalissoboi.goosestation.settings.RewindUsage
import io.github.ilyalissoboi.goosestation.ui.SettingsEntry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The core pages' and the BIOS page's loads, reloads and saves, with a fake core and settings in memory. */
class SettingsPagesTest {
    private val calls = mutableListOf<String>()
    private val api = FakeCoreApi(calls)
    private val settings = MemoryCoreSettings()

    private fun TestScope.pages() = SettingsPages(backgroundScope, StandardTestDispatcher(testScheduler), api, settings)

    @Test
    fun reloadShownReloadsOnlyTheOpenPage() =
        runTest {
            val pages = pages()
            pages.load(CorePage.Graphics)
            runCurrent()
            assertFalse(pages.values(CorePage.Graphics)!![GraphicsSettings.SHOW_FPS])
            settings.setBool("Display", "ShowFPS", true)
            calls.clear()

            pages.reloadShown(SettingsEntry.Graphics)
            // The old values stay shown meanwhile: no loading page, so the focus stays where it was.
            assertNotNull(pages.values(CorePage.Graphics))
            runCurrent()

            assertTrue(pages.values(CorePage.Graphics)!![GraphicsSettings.SHOW_FPS])
            // The core's names are read once.
            assertEquals(emptyList<String>(), calls)
            assertNull(pages.bios)
        }

    @Test
    fun reloadShownReadsTheBiosPageAgain() =
        runTest {
            val pages = pages()
            pages.loadBios()
            runCurrent()
            assertEquals(emptyList<BiosImage>(), pages.bios!!.images)
            api.images = listOf(BiosImage("scph5501.bin", "SCPH-5501", "NTSC-U"))
            calls.clear()

            pages.reloadShown(SettingsEntry.Bios)
            runCurrent()

            assertEquals(api.images, pages.bios!!.images)
            assertEquals(listOf("getBiosImages"), calls)
            assertNull(pages.values(CorePage.Graphics))
        }

    @Test
    fun otherPagesAreNotReloaded() =
        runTest {
            val pages = pages()

            pages.reloadShown(SettingsEntry.Controllers)
            pages.reloadShown(SettingsEntry.Folders)
            pages.reloadShown(null)
            runCurrent()

            assertEquals(emptyList<String>(), calls)
        }

    // A reload whose values were read before the user's change mustn't show the old value again.
    @Test
    fun aReadOvertakenByAChangeIsDropped() =
        runTest {
            val pages = pages()
            pages.load(CorePage.Graphics)
            pages.loadBios()
            runCurrent()

            settings.duringRead = { pages.change(CorePage.Graphics, pages.graphicsWith(showFps = true)) }
            pages.reloadShown(SettingsEntry.Graphics)
            runCurrent()
            api.duringRead = { pages.setFastBoot(true) }
            pages.reloadShown(SettingsEntry.Bios)
            runCurrent()

            assertTrue(pages.values(CorePage.Graphics)!![GraphicsSettings.SHOW_FPS])
            assertTrue(pages.bios!!.fastBoot)
        }

    // The reload's other fields still show once the change is saved: they're read again after it.
    @Test
    fun aReloadOvertakenByAChangeStillShowsTheFile() =
        runTest {
            val pages = pages()
            pages.load(CorePage.Graphics)
            pages.loadBios()
            runCurrent()
            // settings.ini was reloaded with these from elsewhere.
            settings.setString("GPU", "Renderer", "OpenGL")
            api.images = listOf(BiosImage("scph5501.bin", "SCPH-5501", "NTSC-U"))

            settings.duringRead = { pages.change(CorePage.Graphics, pages.graphicsWith(showFps = true)) }
            pages.reloadShown(SettingsEntry.Graphics)
            runCurrent()
            api.duringRead = { pages.setFastBoot(true) }
            pages.reloadShown(SettingsEntry.Bios)
            runCurrent()

            assertEquals("OpenGL", pages.values(CorePage.Graphics)!![GraphicsSettings.RENDERER])
            assertTrue(pages.values(CorePage.Graphics)!![GraphicsSettings.SHOW_FPS])
            assertEquals(api.images, pages.bios!!.images)
            assertTrue(pages.bios!!.fastBoot)
        }

    @Test
    fun changesSaveOnlyWhatChanged() =
        runTest {
            val pages = pages()
            settings.setString("GPU", "Renderer", "Vulkan")
            pages.load(CorePage.Graphics)
            pages.loadBios()
            runCurrent()
            val before = settings.values.toMap()
            calls.clear()

            pages.change(CorePage.Graphics, pages.graphicsWith(showFps = true))
            pages.setBiosOverride(BiosRegion.NtscJ, "scph5500.bin")
            pages.setFastBoot(true)
            runCurrent()

            assertEquals(
                before +
                    mapOf(
                        "Display/ShowFPS" to "true",
                        "BIOS/PathNTSCJ" to "scph5500.bin",
                        "BIOS/PatchFastBoot" to "true",
                    ),
                settings.values,
            )
            assertEquals(listOf("applySettings", "applySettings", "applySettings"), calls)
        }

    @Test
    fun namesAreReadOnce() =
        runTest {
            val pages = pages()
            api.names["GPURenderer"] = listOf("Automatic", "Vulkan")

            pages.load(CorePage.Graphics)
            runCurrent()
            pages.load(CorePage.Graphics)
            runCurrent()

            assertEquals(NameList.entries.map { "getSettingNames ${it.kind}" }, calls)
            assertEquals(listOf("Automatic", "Vulkan"), pages.names[NameList.GPURenderer]!!.map { it.value })
        }

    @Test
    fun aLoadShowsNothingUntilRead() =
        runTest {
            val pages = pages()
            pages.load(CorePage.Graphics)
            runCurrent()

            pages.load(CorePage.Graphics)

            assertNull(pages.values(CorePage.Graphics))
            runCurrent()
            assertNotNull(pages.values(CorePage.Graphics))
        }

    @Test
    fun theRewindEstimateIsReadWithEmulationAndAfterEachChange() =
        runTest {
            val pages = pages()
            pages.load(CorePage.Emulation)
            runCurrent()
            assertEquals(RewindUsage(40L * 1_048_576, 20L * 1_048_576), pages.rewindUsage)
            calls.clear()

            pages.change(
                CorePage.Emulation,
                pages.values(CorePage.Emulation)!!.with(EmulationSettings.REWIND_SLOTS, 20),
            )
            runCurrent()

            assertEquals(listOf("applySettings", "getRewindMemoryUsage 20"), calls)
            assertEquals(RewindUsage(80L * 1_048_576, 40L * 1_048_576), pages.rewindUsage)
        }

    @Test
    fun theSharedCardsAreReadWithMemoryCards() =
        runTest {
            val pages = pages()
            api.sharedCards = listOf("a.mcd", "shared_card_1.mcd")

            pages.load(CorePage.MemoryCards)
            runCurrent()

            assertEquals(listOf("a.mcd", "shared_card_1.mcd"), pages.sharedCards)
            assertNotNull(pages.values(CorePage.MemoryCards))
        }

    private fun SettingsPages.graphicsWith(showFps: Boolean) =
        values(CorePage.Graphics)!!.with(GraphicsSettings.SHOW_FPS, showFps)
}
