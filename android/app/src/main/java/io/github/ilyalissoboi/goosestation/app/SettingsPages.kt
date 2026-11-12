// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.settings.AdvancedSettings
import io.github.ilyalissoboi.goosestation.settings.AudioSettings
import io.github.ilyalissoboi.goosestation.settings.BiosFastBoot
import io.github.ilyalissoboi.goosestation.settings.BiosOverrides
import io.github.ilyalissoboi.goosestation.settings.BiosPage
import io.github.ilyalissoboi.goosestation.settings.BiosRegion
import io.github.ilyalissoboi.goosestation.settings.ConsoleSettings
import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.CoreSettings
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.InterfaceSettings
import io.github.ilyalissoboi.goosestation.settings.MemoryCardSettings
import io.github.ilyalissoboi.goosestation.settings.NameList
import io.github.ilyalissoboi.goosestation.settings.RewindUsage
import io.github.ilyalissoboi.goosestation.settings.Setting
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.SettingsEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The settings pages read and saved as lists of [Setting]s, each the hub's [entry]. */
enum class CorePage(
    val entry: SettingsEntry,
) {
    Interface(SettingsEntry.Interface),
    Console(SettingsEntry.Console),
    Emulation(SettingsEntry.Emulation),
    Graphics(SettingsEntry.Graphics),
    Audio(SettingsEntry.Audio),

    /** Memory cards' card slot rows, beside its sync rows. */
    MemoryCards(SettingsEntry.MemoryCards),
    Advanced(SettingsEntry.Advanced),
    ;

    val title get() = entry.label

    /** The page as both settings owners have it, or null for one only the global settings have. */
    val shared: SharedPage?
        get() =
            when (this) {
                Interface -> null
                Console -> SharedPage.Console
                Emulation -> SharedPage.Emulation
                Graphics -> SharedPage.Graphics
                Audio -> SharedPage.Audio
                MemoryCards -> SharedPage.MemoryCards
                Advanced -> SharedPage.Advanced
            }

    /** The page's settings, in its order. */
    val settings: List<Setting<*>>
        get() =
            when (this) {
                Interface -> InterfaceSettings.all
                Console -> ConsoleSettings.all
                Emulation -> EmulationSettings.all
                Graphics -> GraphicsSettings.all
                Audio -> AudioSettings.all
                MemoryCards -> MemoryCardSettings.all
                Advanced -> AdvancedSettings.all
            }

    companion object {
        /** [entry]'s page, or null for one that isn't a [CorePage]. */
        fun of(entry: SettingsEntry?) = entries.firstOrNull { it.entry == entry }
    }
}

/**
 * The settings pages' values, read and saved through [api] and [settings] on [core], one call at a time: each
 * [CorePage], and the BIOS page. Main thread only. The mirror's next push takes a save's settings.ini to the data
 * folder.
 */
class SettingsPages(
    private val scope: CoroutineScope,
    private val core: CoroutineDispatcher,
    private val api: CoreApi,
    private val settings: CoreSettings,
) {
    private val pages =
        PageValues<CorePage>(scope, core, api, { it.shared }) { page ->
            PageReader({ PageLoad(SettingValues.read(settings, page.settings)) })
        }

    /** What the pages' screens show beside their values. */
    val extras: PageExtras get() = pages

    /** The core's choice lists, read with the first page and kept: they don't change. Empty until then. */
    val names get() = pages.names

    /** What Emulation's rewind states take, as the core said when that page was last read or changed. */
    val rewindUsage get() = pages.rewindUsage

    /** The memory card folder's cards, read with Memory cards' card slots. */
    val sharedCards get() = pages.sharedCards

    /** Whether fast memory access can run on this device, as the core said when Advanced was last read. */
    val fastmemAvailable get() = pages.fastmemAvailable

    /** Vulkan didn't start in this run, as the core said when Graphics was last read. */
    val vulkanFailed get() = pages.vulkanFailed

    /** The BIOS page's images and settings, as read on opening it; null until they are. */
    var bios by mutableStateOf<BiosPage?>(null)
        private set

    // As PageValues' counters: a BIOS read that comes back after another or a change is dropped, and read again once
    // the change is saved.
    private var biosRead = 0
    private var biosReading = false

    /** [page]'s values, as last read or changed; null until read. */
    fun values(page: CorePage): SettingValues? = pages.values(page)

    /** Reads [page]'s values, from none shown. */
    fun load(page: CorePage) = pages.load(page)

    /** Reads the BIOS page's images and settings, from none shown. */
    fun loadBios() {
        bios = null
        readBios()
    }

    /**
     * Reads [page]'s values again if it's a [CorePage] or BIOS, as settings.ini or the BIOS images changed while it
     * was open. The old values stay shown until then, so the page keeps its focus.
     */
    fun reloadShown(page: SettingsEntry?) {
        if (page == SettingsEntry.Bios) return readBios()
        CorePage.of(page)?.let(pages::reread)
    }

    /** Saves [page]'s change to [new], only the settings that changed, then has the core apply it. */
    fun change(
        page: CorePage,
        new: SettingValues,
    ) {
        pages.change(page, new) { values, old ->
            values.save(settings, old)
            api.applySettings()
            ; {}
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
        val reread = biosReading
        biosRead++
        scope.launch(core) {
            BiosOverrides.set(settings, region, fileName)
            api.applySettings()
        }
        if (reread) readBios()
    }

    /** Turns fast boot on or off, then has the core apply it. */
    fun setFastBoot(on: Boolean) {
        val page = bios ?: return
        bios = page.copy(fastBoot = on)
        val reread = biosReading
        biosRead++
        scope.launch(core) {
            BiosFastBoot.save(settings, on)
            api.applySettings()
        }
        if (reread) readBios()
    }

    private fun readBios() {
        val read = ++biosRead
        biosReading = true
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
            if (read != biosRead) return@launch
            biosReading = false
            bios = loaded
        }
    }
}
