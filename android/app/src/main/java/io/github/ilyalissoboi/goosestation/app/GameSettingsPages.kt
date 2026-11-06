// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.TypeSetting
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
import io.github.ilyalissoboi.goosestation.settings.NameSetting
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
                // Read with its pads' types (readControllers), and counted by them.
                Controllers -> emptyList()
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

/**
 * A game's Controllers page: the [pads] its multitap offers (0-based, desktop's order), each one's type's settings
 * ([typeSettings]), and the input profile its file names ([inputProfile]), which makes the page read-only.
 */
data class GameControllers(
    val pads: List<Int>,
    val typeSettings: Map<Int, List<TypeSetting>>,
    val inputProfile: String?,
) {
    companion object {
        /** Each pad's type: `[PadN] Type`, with the core's default (a DualShock on Port 1, nothing elsewhere). */
        fun type(pad: Int) =
            NameSetting("Pad${pad + 1}", "Type", if (pad == 0) "AnalogController" else "None", NameList.ControllerType)

        /** The pads [multitap] (the core's mode name) offers, in desktop's order: a port's slots together. */
        fun pads(multitap: String): List<Int> =
            when (multitap) {
                "Port1Only" -> listOf(0, 2, 3, 4, 1)
                "Port2Only" -> listOf(0, 1, 5, 6, 7)
                "BothPorts" -> listOf(0, 2, 3, 4, 1, 5, 6, 7)
                else -> listOf(0, 1)
            }

        /** [pad]'s label under [multitap]: "Port 1", or "Port 1A" to "Port 2D" on a port with multitap. */
        fun label(
            pad: Int,
            multitap: String,
        ): String {
            val port = if (pad == 0 || pad in 2..4) 1 else 2
            val slot =
                when (pad) {
                    0, 1 -> 0
                    in 2..4 -> pad - 1
                    else -> pad - 4
                }
            val tapped = multitap == "BothPorts" || multitap == (if (port == 1) "Port1Only" else "Port2Only")
            return if (tapped) "Port $port${'A' + slot}" else "Port $port"
        }
    }
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

    /** The Controllers page's pads and their types' settings, as last read; null until read. */
    var controllers by mutableStateOf<GameControllers?>(null)
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
        controllers = null
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
        controllers = null
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
                    if (page == GamePage.Controllers) api.updateGameControllers(key)
                    if (apply) api.applyGameSettings(key)
                    readSummary(key)
                }
            showSummary(summary)
        }
        // A type or multitap changed: the page's rows are another type's, or another port's.
        val retyped =
            page == GamePage.Controllers && new.changed(old).any { it.key == "Type" || it == PortSettings.MULTITAP }
        if (reread || retyped) read(page)
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
        // A game's controller keys count only while its file says so, as the core takes them.
        val controllerKeys =
            if (layer(key).getBool(CONTROLLER_PORTS, USE_GAME_SETTINGS, false)) {
                val typeKeys =
                    api
                        .getSettingNames(NameList.ControllerType.kind)
                        .flatMap { api.getTypeSettings(it.value) }
                        .map { it.key }
                        .toSet() + "Type"
                keys.filter { (section, name) -> section in PADS && name in typeKeys }.toSet() +
                    keys.filter { it == (PortSettings.MULTITAP.section to PortSettings.MULTITAP.key) }
            } else {
                emptySet()
            }
        val counts =
            GamePage.entries.associateWith { page ->
                if (page == GamePage.Controllers) {
                    controllerKeys.size
                } else {
                    page.rows.count { (it.section to it.key) in keys }
                }
            }
        val shownKeys = GamePage.entries.flatMap { page -> page.rows.map { it.section to it.key } }.toSet()
        val others = keys.count { it !in shownKeys && it !in controllerKeys && it !in FLAGS }
        return GameSummary(counts, others, api.getGameInputProfile(key))
    }

    /** On [core]: the Controllers page's values and pads, its own only while its file says they count. */
    private fun readControllers(key: String): Pair<SettingValues, GameControllers> {
        val profile = api.getGameInputProfile(key)
        val file = layer(key)
        val counts = profile == null && file.getBool(CONTROLLER_PORTS, USE_GAME_SETTINGS, false)
        val game = if (counts) file else NoSettings
        val all = (0 until PADS.size).map { GameControllers.type(it) }
        val first = SettingValues.readGame(global, game, listOf(PortSettings.MULTITAP) + all)
        val pads = GameControllers.pads(first[PortSettings.MULTITAP])
        val typeSettings = pads.associateWith { api.getTypeSettings(first[GameControllers.type(it)]) }
        val settings =
            listOf(PortSettings.MULTITAP) +
                pads.flatMap { pad ->
                    listOf(GameControllers.type(pad)) + PortSettings.settings(pad, typeSettings.getValue(pad))
                }
        return SettingValues.readGame(global, game, settings) to GameControllers(pads, typeSettings, profile)
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
                    val controllers = if (page == GamePage.Controllers) readControllers(key) else null
                    PageRead(
                        names = known.ifEmpty { NameList.entries.associateWith { api.getSettingNames(it.kind) } },
                        values = controllers?.first ?: SettingValues.readGame(global, layer(key), page.settings),
                        vulkanFailed = page == GamePage.Graphics && api.isVulkanFailed(),
                        sharedCards = if (page == GamePage.MemoryCards) api.getSharedCardNames() else null,
                        controllers = controllers?.second,
                    )
                }
            if (read != pageRead[page] || game?.serial != key) return@launch
            pageReading[page] = false
            names = loaded.names
            if (page == GamePage.Graphics) vulkanFailed = loaded.vulkanFailed
            loaded.sharedCards?.let { sharedCards = it }
            loaded.controllers?.let { controllers = it }
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
        val controllers: GameControllers? = null,
    )

    /** A file with nothing in it: a game's controller keys, while they don't count. */
    private object NoSettings : LayerSettings {
        override fun contains(
            section: String,
            key: String,
        ) = false

        override fun delete(
            section: String,
            key: String,
        ) = error("read only")

        override fun getString(
            section: String,
            key: String,
            default: String,
        ) = default

        override fun setString(
            section: String,
            key: String,
            value: String?,
        ) = error("read only")

        override fun getInt(
            section: String,
            key: String,
            default: Int,
        ) = default

        override fun setInt(
            section: String,
            key: String,
            value: Int,
        ) = error("read only")

        override fun getBool(
            section: String,
            key: String,
            default: Boolean,
        ) = default

        override fun setBool(
            section: String,
            key: String,
            value: Boolean,
        ) = error("read only")

        override fun getFloat(
            section: String,
            key: String,
            default: Float,
        ) = default

        override fun setFloat(
            section: String,
            key: String,
            value: Float,
        ) = error("read only")
    }

    private companion object {
        const val CONTROLLER_PORTS = "ControllerPorts"
        const val USE_GAME_SETTINGS = "UseGameSettingsForController"

        /** The core's eight pads' sections. */
        val PADS = (1..8).map { "Pad$it" }.toSet()

        /** What only says which settings the controllers take, not a setting of their own. */
        val FLAGS =
            setOf(
                "ControllerPorts" to "UseGameSettingsForController",
                "ControllerPorts" to "GameSettingsInitialized",
            )
    }
}
