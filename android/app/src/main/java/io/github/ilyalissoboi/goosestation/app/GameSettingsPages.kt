// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.settings.AchievementsSettings
import io.github.ilyalissoboi.goosestation.settings.AudioSettings
import io.github.ilyalissoboi.goosestation.settings.ConsoleSettings
import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.CoreSettings
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.Hardcore
import io.github.ilyalissoboi.goosestation.settings.LayerSettings
import io.github.ilyalissoboi.goosestation.settings.MemoryCardSettings
import io.github.ilyalissoboi.goosestation.settings.NameList
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.RewindUsage
import io.github.ilyalissoboi.goosestation.settings.Setting
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A game's own settings pages, in desktop's order, as the game's hub lists them. */
enum class GamePage(
    val label: String,
    val summary: String,
) {
    Console("Console", "Region, CPU, CD-ROM speed"),
    Emulation("Emulation", "Speed, frame pacing, rewind, run-ahead"),
    Graphics("Graphics", "Renderer, enhancements, display, on-screen display"),
    Audio("Audio", "Volume, latency"),
    Controllers("Controllers", "Multitap, each port's type and its settings"),
    MemoryCards("Memory cards", "Card types and shared cards"),
    Achievements("Achievements", "Hardcore mode, messages"),
    ;

    /** What the page's rows write, counted as the game's own settings. */
    val rows: List<Setting<*>>
        get() =
            when (this) {
                Console -> ConsoleSettings.all - Hardcore.settings.toSet()
                Emulation -> EmulationSettings.all - Hardcore.settings.toSet()
                Graphics -> GraphicsSettings.all
                Audio -> AudioSettings.all
                Controllers -> listOf(PortSettings.MULTITAP)
                MemoryCards -> MemoryCardSettings.GAME
                Achievements -> AchievementsSettings.all
            }

    /** What the page reads: its rows, and what they depend on, such as whether hardcore mode is on. */
    val settings: List<Setting<*>>
        get() =
            when (this) {
                Console -> ConsoleSettings.all
                Emulation -> EmulationSettings.all
                Achievements -> AchievementsSettings.all + Hardcore.SIGNED_IN
                else -> rows
            }
}

/**
 * What a game's file sets: how many of each page's rows ([counts]), the [others] no page shows (desktop's own keys,
 * its bindings), and the input profile it names ([inputProfile]), which this app doesn't use.
 */
data class GameSummary(
    val counts: Map<GamePage, Int>,
    val others: Int,
    val inputProfile: String? = null,
) {
    /** The rows the game sets, on all its pages. */
    val total get() = counts.values.sum()
}

/** What a game's hub asks before it acts. */
enum class GameQuestion { Copy, Clear }

/**
 * A game's own settings pages: its [game] and whether it's [running] (each change applies straight away), the hub's
 * [summary], the [page] shown (null for the hub), and each page's values, its own over the [global] ones, read and
 * saved through [api] and [layer] (the game's file, by its key) on [core], one call at a time. Main thread only. The
 * mirror's next push takes the file to the data folder, or deletes it there once it's empty.
 */
class GameSettingsPages(
    private val scope: CoroutineScope,
    private val core: CoroutineDispatcher,
    private val api: CoreApi,
    private val global: CoreSettings,
    private val layer: (String) -> LayerSettings,
) {
    /** The game whose settings show; null while none do. */
    var game by mutableStateOf<GameEntry?>(null)
        private set

    /** Its game is running: each change applies straight away, and some only at its next start. */
    var running by mutableStateOf(false)
        private set

    /** The page shown; null for the hub. */
    var page by mutableStateOf<GamePage?>(null)
        private set

    /** The page last left, which the hub focuses. */
    var cameFrom by mutableStateOf<GamePage?>(null)
        private set

    /** What the game's file sets, as last read; null until read. */
    var summary by mutableStateOf<GameSummary?>(null)
        private set

    /** What the hub is asking before it acts; null while it isn't. */
    var asking by mutableStateOf<GameQuestion?>(null)
        private set

    /** The core's choice lists, read with the first page and kept. Empty until then. */
    var names by mutableStateOf<CoreNames>(emptyMap())
        private set

    /** The memory card folder's cards, read with Memory cards. */
    var sharedCards by mutableStateOf(emptyList<String>())
        private set

    /** Vulkan didn't start in this run, as the core said when Graphics was last read. */
    var vulkanFailed by mutableStateOf(false)
        private set

    /** What Emulation's rewind states take, as the core said when that page was last read. */
    var rewindUsage by mutableStateOf<RewindUsage?>(null)
        private set

    private val shown = mutableStateMapOf<GamePage, SettingValues>()

    // As SettingsPages': each changes as a read is asked for or a change is made, so a read that comes back after
    // either is dropped, and read again once the change is saved.
    private val pageRead = mutableMapOf<GamePage, Int>()
    private val pageReading = mutableMapOf<GamePage, Boolean>()
    private var summaryRead = 0

    /** [page]'s values, as last read or changed; null until read. */
    fun values(page: GamePage): SettingValues? = shown[page]

    /** Shows [game]'s hub and reads its summary; a [running] game's changes apply straight away. */
    fun open(
        game: GameEntry,
        running: Boolean,
    ) {
        this.game = game
        this.running = running
        page = null
        cameFrom = null
        asking = null
        summary = null
        shown.clear()
        readSummary()
    }

    /** Forgets the game: nothing shows. */
    fun close() {
        game = null
        page = null
        cameFrom = null
        asking = null
        summary = null
        shown.clear()
    }

    /** Shows [page], once its values are read. */
    fun openPage(page: GamePage) {
        if (game == null) return
        this.page = page
        shown.remove(page)
        read(page)
    }

    /** Back to the hub, which focuses the page left. */
    fun closePage() {
        cameFrom = page ?: return
        page = null
    }

    /** Saves [page]'s change to [new], only what changed, then applies it in the running game. */
    fun change(
        page: GamePage,
        new: SettingValues,
    ) {
        val key = game?.serial ?: return
        val old = shown[page] ?: return
        if (new == old) return
        shown[page] = new
        val reread = pageReading[page] == true
        pageRead[page] = (pageRead[page] ?: 0) + 1
        val apply = running
        summaryRead++
        scope.launch {
            val summary =
                withContext(core) {
                    new.save(layer(key), old)
                    if (apply) api.applyGameSettings(key)
                    readSummary(key)
                }
            showSummary(summary)
        }
        if (reread) read(page)
    }

    fun ask(question: GameQuestion) {
        if (game != null) asking = question
    }

    fun cancelQuestion() {
        asking = null
    }

    /** Does what the hub asked: copies the global settings over the game's, or clears its file. */
    fun confirm() {
        val question = asking ?: return
        val key = game?.serial ?: return
        asking = null
        val apply = running
        scope.launch {
            withContext(core) {
                when (question) {
                    GameQuestion.Copy -> api.copyGlobalSettingsToGame(key)
                    GameQuestion.Clear -> api.clearGameSettings(key)
                }
                if (apply) api.applyGameSettings(key)
            }
            reloadShown()
        }
    }

    /** Reads the summary and the page shown again, as the game's file changed under them; the old values stay shown. */
    fun reloadShown() {
        if (game == null) return
        readSummary()
        page?.let { if (it in shown) read(it) }
    }

    private fun readSummary() {
        val key = game?.serial ?: return
        val read = ++summaryRead
        scope.launch {
            val summary = withContext(core) { readSummary(key) }
            if (read == summaryRead) this@GameSettingsPages.summary = summary
        }
    }

    private fun showSummary(summary: GameSummary) {
        this.summary = summary
    }

    /** On [core]. */
    private fun readSummary(key: String): GameSummary {
        val keys = api.getGameSettingKeys(key).toSet()
        val counts =
            GamePage.entries.associateWith { page -> page.rows.count { (it.section to it.key) in keys } }
        val shownKeys = GamePage.entries.flatMap { page -> page.rows.map { it.section to it.key } }.toSet()
        val others = keys.count { it !in shownKeys && it !in FLAGS }
        return GameSummary(counts, others, api.getGameInputProfile(key))
    }

    // Queued on core after any save launched before it, so it reads what that save wrote.
    private fun read(page: GamePage) {
        val key = game?.serial ?: return
        val read = (pageRead[page] ?: 0) + 1
        pageRead[page] = read
        pageReading[page] = true
        val known = names
        scope.launch {
            val loaded =
                withContext(core) {
                    PageRead(
                        names = known.ifEmpty { NameList.entries.associateWith { api.getSettingNames(it.kind) } },
                        values = SettingValues.readGame(global, layer(key), page.settings),
                        vulkanFailed = page == GamePage.Graphics && api.isVulkanFailed(),
                        sharedCards = if (page == GamePage.MemoryCards) api.getSharedCardNames() else null,
                    )
                }
            if (read != pageRead[page] || game?.serial != key) return@launch
            pageReading[page] = false
            names = loaded.names
            if (page == GamePage.Graphics) vulkanFailed = loaded.vulkanFailed
            loaded.sharedCards?.let { sharedCards = it }
            if (page == GamePage.Emulation) {
                rewindUsage =
                    withContext(core) { api.getRewindMemoryUsage(loaded.values[EmulationSettings.REWIND_SLOTS]) }
            }
            shown[page] = loaded.values
        }
    }

    private data class PageRead(
        val names: CoreNames,
        val values: SettingValues,
        val vulkanFailed: Boolean,
        val sharedCards: List<String>?,
    )

    private companion object {
        /** What only says which settings the controllers take, not a setting of their own. */
        val FLAGS =
            setOf(
                "ControllerPorts" to "UseGameSettingsForController",
                "ControllerPorts" to "GameSettingsInitialized",
            )
    }
}
