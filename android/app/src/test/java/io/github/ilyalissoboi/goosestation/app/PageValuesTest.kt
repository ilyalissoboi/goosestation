// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.settings.AdvancedSettings
import io.github.ilyalissoboi.goosestation.settings.AudioSettings
import io.github.ilyalissoboi.goosestation.settings.ConsoleSettings
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.MemoryCardSettings
import io.github.ilyalissoboi.goosestation.settings.MemoryCoreSettings
import io.github.ilyalissoboi.goosestation.settings.RewindUsage
import io.github.ilyalissoboi.goosestation.settings.SettingValues
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

/** The page model both settings owners share ([PageValues]), over pages named by [SharedPage], with a fake core. */
class PageValuesTest {
    private val calls = mutableListOf<String>()
    private val api = FakeCoreApi(calls)
    private val settings = MemoryCoreSettings()
    private var current = true
    private val thens = mutableListOf<String>()

    private fun TestScope.model(): PageValues<String> =
        PageValues(backgroundScope, StandardTestDispatcher(testScheduler), api, ::sharedOf) { page ->
            PageReader(
                load = {
                    calls += "load $page"
                    PageLoad(SettingValues.read(settings, settingsOf(page))) { thens += page }
                },
                current = { current },
            )
        }

    private fun sharedOf(page: String) = SharedPage.entries.firstOrNull { it.name == page }

    private fun settingsOf(page: String) =
        when (SharedPage.valueOf(page)) {
            SharedPage.Console -> ConsoleSettings.all
            SharedPage.Emulation -> EmulationSettings.all
            SharedPage.Graphics -> GraphicsSettings.all
            SharedPage.Audio -> AudioSettings.all
            SharedPage.MemoryCards -> MemoryCardSettings.all
            SharedPage.Advanced -> AdvancedSettings.all
        }

    /** A save as the global pages make one: the changed settings, then nothing more on the main thread. */
    private fun save(
        new: SettingValues,
        old: SettingValues,
    ): () -> Unit {
        calls += "save"
        new.save(settings, old)
        return {}
    }

    private fun TestScope.shown(
        model: PageValues<String>,
        page: String,
    ) = model.also {
        it.load(page)
        runCurrent()
        calls.clear()
        thens.clear()
    }

    // Review Focus 5.
    @Test
    fun aReadShowsItsValuesAndReadsTheNamesOnce() =
        runTest {
            api.names["GPURenderer"] = listOf("Vulkan")
            val model = model()

            model.load("Console")
            runCurrent()
            model.load("Audio")
            runCurrent()

            assertNotNull(model.values("Console"))
            assertNotNull(model.values("Audio"))
            assertTrue(model.isShown("Audio"))
            assertEquals(1, calls.count { it == "getSettingNames GPURenderer" })
            assertEquals(listOf("Console", "Audio"), thens)
        }

    @Test
    fun eachSharedPageReadsItsExtras() =
        runTest {
            api.vulkanFailed = true
            api.fastmemAvailable = false
            api.sharedCards = listOf("shared.mcd")
            val model = model()

            model.load("Console")
            runCurrent()
            assertFalse(model.vulkanFailed)
            assertTrue(model.fastmemAvailable)
            assertNull(model.rewindUsage)
            assertEquals(emptyList<String>(), model.sharedCards)
            assertTrue(calls.none { it.startsWith("getRewindMemoryUsage") || it == "getSharedCardNames" })

            for (page in listOf("Graphics", "Advanced", "Emulation", "MemoryCards")) model.load(page)
            runCurrent()
            assertTrue(model.vulkanFailed)
            assertFalse(model.fastmemAvailable)
            val slots = model.values("Emulation")!![EmulationSettings.REWIND_SLOTS]
            assertEquals(
                RewindUsage(api.rewindPerSlot.ramBytes * slots, api.rewindPerSlot.vramBytes * slots),
                model.rewindUsage,
            )
            assertEquals(listOf("shared.mcd"), model.sharedCards)
        }

    @Test
    fun aReadOvertakenByANewerOneIsDropped() =
        runTest {
            val model = model()

            model.load("Console")
            settings.setString("Console", "Region", "NTSC-J")
            model.load("Console")
            runCurrent()

            assertEquals("NTSC-J", model.values("Console")!![ConsoleSettings.REGION])
            assertEquals(listOf("Console"), thens)
        }

    @Test
    fun aReadNoLongerCurrentIsDropped() =
        runTest {
            val model = model()

            model.load("Console")
            current = false
            runCurrent()

            assertNull(model.values("Console"))
            assertEquals(emptyList<String>(), thens)
        }

    // Review Focus 2: the change shows at once, the read out is dropped, and a read after the save reads what it wrote.
    @Test
    fun aChangeDuringAReadReadsAgainAfterItsSave() =
        runTest {
            val model = shown(model(), "Console")
            model.reread("Console")
            val changed = model.values("Console")!!.with(ConsoleSettings.REGION, "PAL")

            assertTrue(model.change("Console", changed, save = ::save))
            assertEquals("PAL", model.values("Console")!![ConsoleSettings.REGION])
            runCurrent()

            assertEquals(
                listOf("load Console", "save", "load Console"),
                calls.filter {
                    it == "save" ||
                        it.startsWith("load")
                },
            )
            assertEquals("PAL", model.values("Console")!![ConsoleSettings.REGION])
            assertEquals(listOf("Console"), thens)
        }

    // Review Focus 3.
    @Test
    fun aChangeThatChangesNothingSavesNothing() =
        runTest {
            val model = shown(model(), "Console")

            assertFalse(model.change("Console", model.values("Console")!!, save = ::save))
            assertFalse(model.change("Audio", model.values("Console")!!, save = ::save))
            runCurrent()

            assertTrue(calls.none { it == "save" })
        }

    // Review Focus 4: two quick changes of the save slots keep the estimate for the values still shown.
    @Test
    fun theEstimateFollowsAChangeAndOnlyTheLastOne() =
        runTest {
            val model = shown(model(), "Emulation")
            val values = model.values("Emulation")!!
            val before = model.rewindUsage
            // The slots change to 40 as 30's estimate is read: 30's lands after, when 40 shows, and is dropped.
            var asFortyIsAsked: RewindUsage? = null
            api.onRewind = {
                model.change("Emulation", values.with(EmulationSettings.REWIND_SLOTS, 40), save = ::save)
                api.onRewind = { asFortyIsAsked = model.rewindUsage }
            }

            model.change("Emulation", values.with(EmulationSettings.REWIND_SLOTS, 30), save = ::save)
            runCurrent()

            assertTrue("getRewindMemoryUsage 30" in calls)
            assertEquals(before, asFortyIsAsked)
            assertEquals(usage(40), model.rewindUsage)
        }

    private fun usage(slots: Int) = RewindUsage(api.rewindPerSlot.ramBytes * slots, api.rewindPerSlot.vramBytes * slots)

    @Test
    fun aChangeRunsWhatItsSaveReturned() =
        runTest {
            val model = shown(model(), "Console")
            val after = mutableListOf<String>()

            model.change("Console", model.values("Console")!!.with(ConsoleSettings.REGION, "PAL")) { new, old ->
                new.save(settings, old)
                after += "saved"
                ; { after += "then" }
            }
            assertEquals(emptyList<String>(), after)
            runCurrent()

            assertEquals(listOf("saved", "then"), after)
        }

    @Test
    fun readAgainReadsAfterTheSave() =
        runTest {
            val model = shown(model(), "Console")

            model.change(
                "Console",
                model.values("Console")!!.with(ConsoleSettings.REGION, "PAL"),
                readAgain = true,
                save = ::save,
            )
            runCurrent()

            assertEquals(listOf("save", "load Console"), calls.filter { it == "save" || it.startsWith("load") })
        }

    @Test
    fun clearKeepsTheExtras() =
        runTest {
            api.names["GPURenderer"] = listOf("Vulkan")
            val model = shown(model(), "Console")
            val names = model.names

            model.clear()

            assertNull(model.values("Console"))
            assertFalse(model.isShown("Console"))
            assertEquals(names, model.names)
            assertTrue(names.isNotEmpty())
        }

    @Test
    fun rereadReadsOnlyAShownPage() =
        runTest {
            val model = shown(model(), "Console")

            model.reread("Audio")
            model.reread("Console")
            runCurrent()

            assertEquals(listOf("load Console"), calls.filter { it.startsWith("load") })
            assertNull(model.values("Audio"))
        }

    @Test
    fun aPageWithNothingToReadStaysUnread() =
        runTest {
            val model =
                PageValues<String>(backgroundScope, StandardTestDispatcher(testScheduler), api, ::sharedOf) { null }

            model.load("Console")
            runCurrent()

            assertNull(model.values("Console"))
            assertTrue(calls.isEmpty())
        }
}
