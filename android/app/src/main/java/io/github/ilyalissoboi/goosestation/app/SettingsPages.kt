// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.settings.BiosFastBoot
import io.github.ilyalissoboi.goosestation.settings.BiosOverrides
import io.github.ilyalissoboi.goosestation.settings.BiosPage
import io.github.ilyalissoboi.goosestation.settings.BiosRegion
import io.github.ilyalissoboi.goosestation.settings.CoreSettings
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.ui.SettingsEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Graphics and BIOS pages' values, read and saved through [api] and [settings] on [core], one call at a time. Main
 * thread only. The mirror's next push takes a save's settings.ini to the data folder.
 */
class SettingsPages(
    private val scope: CoroutineScope,
    private val core: CoroutineDispatcher,
    private val api: CoreApi,
    private val settings: CoreSettings,
) {
    /** The Graphics page's settings, as loaded on opening it; null until they are. */
    var graphics by mutableStateOf<GraphicsSettings?>(null)
        private set

    /** The core's `[Display] AspectRatio` names, read with [graphics]. */
    var aspectRatioNames by mutableStateOf(emptyList<String>())
        private set

    /** Vulkan didn't start in this run, as the core said when [graphics] were read. */
    var vulkanFailed by mutableStateOf(false)
        private set

    /** The BIOS page's images and settings, as read on opening it; null until they are. */
    var bios by mutableStateOf<BiosPage?>(null)
        private set

    // Change as each read is asked for or a change is made, so a read that comes back after either is dropped.
    private var graphicsRead = 0
    private var biosRead = 0

    /** Reads the Graphics page's settings, from none shown. */
    fun loadGraphics() {
        graphics = null
        readGraphics()
    }

    /** Reads the BIOS page's images and settings, from none shown. */
    fun loadBios() {
        bios = null
        readBios()
    }

    /**
     * Reads [page]'s values again if it's Graphics or BIOS, as settings.ini or the BIOS images changed while it was
     * open. The old values stay shown until then, so the page keeps its focus.
     */
    fun reloadShown(page: SettingsEntry?) {
        when (page) {
            SettingsEntry.Graphics -> readGraphics()
            SettingsEntry.Bios -> readBios()
            else -> {}
        }
    }

    /** Saves the Graphics page's change to [new], only the fields that changed, then has the core apply it. */
    fun changeGraphics(new: GraphicsSettings) {
        val old = graphics ?: return
        if (new == old) return
        // Now, so a second change made before this one is saved starts from it.
        graphics = new
        graphicsRead++
        scope.launch(core) {
            new.save(settings, old)
            api.applySettings()
        }
    }

    /** Sets [region]'s BIOS image to [fileName], or back to Automatic with null, then has the core apply it. */
    fun setBiosOverride(
        region: BiosRegion,
        fileName: String?,
    ) {
        val page = bios ?: return
        val overrides = if (fileName == null) page.overrides - region else page.overrides + (region to fileName)
        // Now, so a second change made before this one is saved starts from it.
        bios = page.copy(overrides = overrides)
        biosRead++
        scope.launch(core) {
            BiosOverrides.set(settings, region, fileName)
            api.applySettings()
        }
    }

    /** Turns fast boot on or off, then has the core apply it. */
    fun setFastBoot(on: Boolean) {
        val page = bios ?: return
        bios = page.copy(fastBoot = on)
        biosRead++
        scope.launch(core) {
            BiosFastBoot.save(settings, on)
            api.applySettings()
        }
    }

    private fun readGraphics() {
        val read = ++graphicsRead
        scope.launch {
            val loaded =
                withContext(core) {
                    Triple(GraphicsSettings.load(settings), api.getAspectRatioNames(), api.isVulkanFailed())
                }
            if (read != graphicsRead) return@launch
            aspectRatioNames = loaded.second
            vulkanFailed = loaded.third
            graphics = loaded.first
        }
    }

    private fun readBios() {
        val read = ++biosRead
        scope.launch {
            val loaded =
                withContext(core) {
                    BiosPage(
                        images = api.getBiosImages(),
                        auto = BiosRegion.entries.associateWith { api.getAutoBios(it.coreName) },
                        overrides = BiosOverrides.load(settings),
                        fastBoot = BiosFastBoot.load(settings),
                    )
                }
            if (read == biosRead) bios = loaded
        }
    }
}
