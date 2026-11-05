// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    ;

    val title get() = entry.label

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
    private val shown = mutableStateMapOf<CorePage, SettingValues>()

    /** The core's choice lists, read with the first page and kept: they don't change. Empty until then. */
    var names by mutableStateOf<CoreNames>(emptyMap())
        private set

    /** What Emulation's rewind states take, as the core said when that page was last read or changed. */
    var rewindUsage by mutableStateOf<RewindUsage?>(null)
        private set

    /** The memory card folder's cards, read with Memory cards' card slots. */
    var sharedCards by mutableStateOf(emptyList<String>())
        private set

    /** Vulkan didn't start in this run, as the core said when Graphics was last read. */
    var vulkanFailed by mutableStateOf(false)
        private set

    /** The BIOS page's images and settings, as read on opening it; null until they are. */
    var bios by mutableStateOf<BiosPage?>(null)
        private set

    // Change as each read is asked for or a change is made, so a read that comes back after either is dropped. A
    // change made while a read is out reads again once it's saved, so the values the dropped one had still show.
    private val pageRead = mutableMapOf<CorePage, Int>()
    private val pageReading = mutableMapOf<CorePage, Boolean>()
    private var biosRead = 0
    private var biosReading = false

    /** [page]'s values, as last read or changed; null until read. */
    fun values(page: CorePage): SettingValues? = shown[page]

    /** Reads [page]'s values, from none shown. */
    fun load(page: CorePage) {
        shown.remove(page)
        read(page)
    }

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
        CorePage.of(page)?.let { if (it in shown) read(it) }
    }

    /** Saves [page]'s change to [new], only the settings that changed, then has the core apply it. */
    fun change(
        page: CorePage,
        new: SettingValues,
    ) {
        val old = shown[page] ?: return
        if (new == old) return
        // Now, so a second change made before this one is saved starts from it.
        shown[page] = new
        val reread = pageReading[page] == true
        pageRead[page] = (pageRead[page] ?: 0) + 1
        scope.launch {
            val usage =
                withContext(core) {
                    new.save(settings, old)
                    api.applySettings()
                    // The core estimates from the settings it has: these, now applied.
                    if (page ==
                        CorePage.Emulation
                    ) {
                        api.getRewindMemoryUsage(new[EmulationSettings.REWIND_SLOTS])
                    } else {
                        null
                    }
                }
            if (usage != null && new == shown[page]) rewindUsage = usage
        }
        if (reread) read(page)
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

    // Queued on core after any save launched before it, so it reads what that save wrote.
    private fun read(page: CorePage) {
        val read = (pageRead[page] ?: 0) + 1
        pageRead[page] = read
        pageReading[page] = true
        val known = names
        scope.launch {
            val loaded =
                withContext(core) {
                    PageRead(
                        names = known.ifEmpty { NameList.entries.associateWith { api.getSettingNames(it.kind) } },
                        values = SettingValues.read(settings, page.settings),
                        vulkanFailed = page == CorePage.Graphics && api.isVulkanFailed(),
                    ).let {
                        when (page) {
                            CorePage.Emulation ->
                                it.copy(
                                    rewindUsage = api.getRewindMemoryUsage(it.values[EmulationSettings.REWIND_SLOTS]),
                                )
                            CorePage.MemoryCards -> it.copy(sharedCards = api.getSharedCardNames())
                            else -> it
                        }
                    }
                }
            if (read != pageRead[page]) return@launch
            pageReading[page] = false
            names = loaded.names
            if (page == CorePage.Graphics) vulkanFailed = loaded.vulkanFailed
            loaded.rewindUsage?.let { rewindUsage = it }
            loaded.sharedCards?.let { sharedCards = it }
            shown[page] = loaded.values
        }
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

/** What a page's read found. */
private data class PageRead(
    val names: CoreNames,
    val values: SettingValues,
    val vulkanFailed: Boolean,
    val rewindUsage: RewindUsage? = null,
    val sharedCards: List<String>? = null,
)
