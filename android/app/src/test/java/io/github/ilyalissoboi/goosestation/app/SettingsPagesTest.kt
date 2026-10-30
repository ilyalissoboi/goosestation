// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.settings.BiosRegion
import io.github.ilyalissoboi.goosestation.settings.MemoryCoreSettings
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

/** The Graphics and BIOS pages' loads, reloads and saves, with a fake core and settings in memory. */
class SettingsPagesTest {
    private val calls = mutableListOf<String>()
    private val api = FakeCoreApi(calls)
    private val settings = MemoryCoreSettings()

    private fun TestScope.pages() = SettingsPages(backgroundScope, StandardTestDispatcher(testScheduler), api, settings)

    @Test
    fun reloadShownReloadsOnlyTheOpenPage() =
        runTest {
            val pages = pages()
            pages.loadGraphics()
            runCurrent()
            assertFalse(pages.graphics!!.showFps)
            settings.setBool("Display", "ShowFPS", true)
            calls.clear()

            pages.reloadShown(SettingsEntry.Graphics)
            // The old values stay shown meanwhile: no loading page, so the focus stays where it was.
            assertNotNull(pages.graphics)
            runCurrent()

            assertTrue(pages.graphics!!.showFps)
            assertEquals(listOf("getAspectRatioNames"), calls)
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
            assertNull(pages.graphics)
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
            pages.loadGraphics()
            pages.loadBios()
            runCurrent()

            api.duringRead = { pages.changeGraphics(pages.graphics!!.copy(showFps = true)) }
            pages.reloadShown(SettingsEntry.Graphics)
            runCurrent()
            api.duringRead = { pages.setFastBoot(true) }
            pages.reloadShown(SettingsEntry.Bios)
            runCurrent()

            assertTrue(pages.graphics!!.showFps)
            assertTrue(pages.bios!!.fastBoot)
        }

    @Test
    fun changesSaveOnlyWhatChanged() =
        runTest {
            val pages = pages()
            settings.setString("GPU", "Renderer", "Vulkan")
            pages.loadGraphics()
            pages.loadBios()
            runCurrent()
            val before = settings.values.toMap()
            calls.clear()

            pages.changeGraphics(pages.graphics!!.copy(showFps = true))
            pages.setBiosOverride(BiosRegion.NtscJ, "scph5500.bin")
            pages.setFastBoot(true)
            runCurrent()

            assertEquals(
                before + mapOf("Display/ShowFPS" to "true", "BIOS/PathNTSCJ" to "scph5500.bin", "BIOS/PatchFastBoot" to "true"),
                settings.values,
            )
            assertEquals(listOf("applySettings", "applySettings", "applySettings"), calls)
        }
}
