// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings
import io.github.ilyalissoboi.goosestation.settings.NameList
import io.github.ilyalissoboi.goosestation.settings.RewindUsage
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The pages both settings owners have, the global ones ([CorePage]) and a game's ([GamePage]), by what a read of each
 * fetches beside its values: the rewind estimate for Emulation, whether Vulkan failed for Graphics, whether fast memory
 * access can run for Advanced, and the memory card folder's cards for Memory cards.
 */
enum class SharedPage { Console, Emulation, Graphics, Audio, MemoryCards, Advanced }

/** One read's [values], and what its owner does with the rest of the read, on the main thread, if the read lands. */
class PageLoad(
    val values: SettingValues,
    val then: () -> Unit = {},
)

/** One read of a page: [load], on the core thread; dropped if it's no longer [current] once it's back. */
class PageReader(
    val load: () -> PageLoad,
    val current: () -> Boolean = { true },
)

/** What a page's screen shows beside its values. */
interface PageExtras {
    /** The core's choice lists, read with the first page and kept: they don't change. Empty until then. */
    val names: CoreNames

    /** The memory card folder's cards, read with Memory cards. */
    val sharedCards: List<String>

    /** Vulkan didn't start in this run, as the core said when Graphics was last read. */
    val vulkanFailed: Boolean

    /** Whether fast memory access can run on this device, as the core said when Advanced was last read. */
    val fastmemAvailable: Boolean

    /** What Emulation's rewind states take, as the core said when that page was last read or changed. */
    val rewindUsage: RewindUsage?
}

/**
 * The values a settings owner's pages show, and their [PageExtras]: read and saved on [core] through [api], one call at
 * a time. Main thread only. Each page is a [shared] one, or none, which decides what its read fetches beside its
 * values. [reader] says, as each read starts, how to read the page; null when there's nothing to read it from.
 */
class PageValues<P : Any>(
    private val scope: CoroutineScope,
    private val core: CoroutineDispatcher,
    private val api: CoreApi,
    private val shared: (P) -> SharedPage?,
    private val reader: (P) -> PageReader?,
) : PageExtras {
    private val shown = mutableStateMapOf<P, SettingValues>()

    override var names by mutableStateOf<CoreNames>(emptyMap())
        private set

    override var sharedCards by mutableStateOf(emptyList<String>())
        private set

    override var vulkanFailed by mutableStateOf(false)
        private set

    override var fastmemAvailable by mutableStateOf(true)
        private set

    override var rewindUsage by mutableStateOf<RewindUsage?>(null)
        private set

    // Change as each read is asked for or a change is made, so a read that comes back after either is dropped. A
    // change made while a read is out reads again once it's saved, so the values the dropped one had still show.
    private val pageRead = mutableMapOf<P, Int>()
    private val pageReading = mutableMapOf<P, Boolean>()

    /** [page]'s values, as last read or changed; null until read. */
    fun values(page: P): SettingValues? = shown[page]

    fun isShown(page: P): Boolean = page in shown

    /** Reads [page]'s values, from none shown. */
    fun load(page: P) {
        shown.remove(page)
        read(page)
    }

    /** Reads [page]'s values again if they show, as their file changed; the old values stay shown until then. */
    fun reread(page: P) {
        if (page in shown) read(page)
    }

    /** Forgets the values shown; the read counters and the extras stay. */
    fun clear() {
        shown.clear()
    }

    /**
     * Saves [page]'s change to [new], if it changes anything, with [save] on [core] (it saves what changed and applies
     * it), then reads Emulation's estimate again. [save] returns what its owner does after, on the main thread. With
     * [readAgain], the page is read again once it's saved, as it is when a read was out. Returns whether it saved.
     */
    fun change(
        page: P,
        new: SettingValues,
        readAgain: Boolean = false,
        save: (new: SettingValues, old: SettingValues) -> () -> Unit,
    ): Boolean {
        val old = shown[page] ?: return false
        if (new == old) return false
        // Now, so a second change made before this one is saved starts from it.
        shown[page] = new
        val reread = pageReading[page] == true
        pageRead[page] = (pageRead[page] ?: 0) + 1
        scope.launch {
            val (after, usage) =
                withContext(core) {
                    val after = save(new, old)
                    // The core estimates from the settings it has: these, now saved and applied.
                    val emulation = shared(page) == SharedPage.Emulation
                    after to if (emulation) api.getRewindMemoryUsage(new[EmulationSettings.REWIND_SLOTS]) else null
                }
            after()
            if (usage != null && new == shown[page]) rewindUsage = usage
        }
        if (reread || readAgain) read(page)
        return true
    }

    // Queued on core after any save launched before it, so it reads what that save wrote.
    private fun read(page: P) {
        val reader = reader(page) ?: return
        val read = (pageRead[page] ?: 0) + 1
        pageRead[page] = read
        pageReading[page] = true
        val known = names
        val kind = shared(page)
        scope.launch {
            val loaded =
                withContext(core) {
                    val names = known.ifEmpty { NameList.entries.associateWith { api.getSettingNames(it.kind) } }
                    val load = reader.load()
                    PageRead(
                        names = names,
                        load = load,
                        vulkanFailed = kind == SharedPage.Graphics && api.isVulkanFailed(),
                        fastmemAvailable = kind != SharedPage.Advanced || api.isFastmemAvailable(),
                        rewindUsage =
                            if (kind == SharedPage.Emulation) {
                                api.getRewindMemoryUsage(load.values[EmulationSettings.REWIND_SLOTS])
                            } else {
                                null
                            },
                        sharedCards = if (kind == SharedPage.MemoryCards) api.getSharedCardNames() else null,
                    )
                }
            if (read != pageRead[page] || !reader.current()) return@launch
            pageReading[page] = false
            names = loaded.names
            if (kind == SharedPage.Graphics) vulkanFailed = loaded.vulkanFailed
            if (kind == SharedPage.Advanced) fastmemAvailable = loaded.fastmemAvailable
            loaded.rewindUsage?.let { rewindUsage = it }
            loaded.sharedCards?.let { sharedCards = it }
            loaded.load.then()
            shown[page] = loaded.load.values
        }
    }

    /** What a page's read found. */
    private class PageRead(
        val names: CoreNames,
        val load: PageLoad,
        val vulkanFailed: Boolean,
        val fastmemAvailable: Boolean,
        val rewindUsage: RewindUsage?,
        val sharedCards: List<String>?,
    )
}
