// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.AchievementSection
import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.GameAchievements
import io.github.ilyalissoboi.goosestation.core.GameDisc
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.Leaderboard
import io.github.ilyalissoboi.goosestation.core.LeaderboardPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the slot panel is for: saving to a slot, or loading one. */
enum class SlotMode { Save, Load }

/**
 * One slot as the slot list shows it: its [slot] ([SaveSlots.RESUME] for the resume state), when it was saved (Unix
 * seconds; null for an empty slot), and its [picture], once it has been read. [owner] is null for the game's own slots,
 * or the serial another disc's state is still filed under; [disc] is the disc it was made on, shown for a multi-disc
 * game only. A [shared] slot is desktop's global one; once read, it [holds] a game (null while unknown).
 */
data class SlotEntry(
    val slot: Int,
    val savedAt: Long?,
    val picture: ArgbImage? = null,
    val owner: String? = null,
    val disc: Int? = null,
    val shared: Boolean = false,
    val holds: HeldGame? = null,
)

/**
 * The slot panel: its [mode], its [entries] (null while they're read), the slot to [focus] as it opens (null for none),
 * the slot it asks to replace, if any ([replacing], in the open [tab]), whether a save or load is under way ([busy]),
 * and the last one's failure, as shown ([error]). In Load, [others] are the states still filed under the game's other
 * discs. The Shared tab lists the [shared] slots (null while they're read), focusing [sharedFocus]. A game without a
 * key ([hasKey] false) has no slots of its own: it opens on Shared. In Load, [deleting] is the save it asks to delete,
 * and [focusRow], when set, the row to focus (the one after a deleted save), before [focus] and [sharedFocus].
 */
data class SlotPanel(
    val mode: SlotMode,
    val entries: List<SlotEntry>? = null,
    val focus: Int? = null,
    val replacing: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val others: List<SlotEntry> = emptyList(),
    val tab: SlotTab = SlotTab.ThisGame,
    val shared: List<SlotEntry>? = null,
    val sharedFocus: Int? = null,
    val hasKey: Boolean = true,
    val deleting: SlotEntry? = null,
    val focusRow: SlotEntry? = null,
)

/**
 * The Change disc panel: the game's [discs], the [current] one's number, whether a disc is going in ([busy]), and why
 * the last one didn't ([error]).
 */
data class DiscChoice(
    val discs: List<GameDisc>,
    val current: Int,
    val busy: Boolean = false,
    val error: String? = null,
)

/** The Achievements panel's tabs. */
enum class CheevoTab { Achievements, Leaderboards }

/**
 * The Achievements panel: its open [tab]; the Achievements tab's [sections] and the Leaderboards tab's [leaderboards]
 * (each null until read); and the leaderboard [open] in it, its [page] once fetched or why it wasn't ([pageError]),
 * and the [request] it was fetched by.
 */
data class AchievementsPanelState(
    val tab: CheevoTab,
    val sections: List<AchievementSection>? = null,
    val leaderboards: List<Leaderboard>? = null,
    val open: Leaderboard? = null,
    val page: LeaderboardPage? = null,
    val pageError: String? = null,
    val request: Int = 0,
)

/**
 * The Patch codes panel: the game's codes in their [groups] (null while they're read), the player's file to add codes
 * in ([fileName]), the manual code last applied ([applied], as said), and why the last change didn't work ([error]).
 */
data class CodePanel(
    val groups: List<PatchCodeGroup>? = null,
    val fileName: String = "",
    val applied: String? = null,
    val error: String? = null,
)

/**
 * The in-game menu: whether the game has [booted] (until then only Resume and Exit game work), whether fast forward is
 * on, Port 1's analog mode (null when it isn't a DualShock: the row is hidden), the slot panel, if it's open,
 * whether it asks before a reset or (while the game is writing a memory card, [cardBusy]) an exit, whether Exit game
 * asks before it replaces the resume state saved at [replacingResume] (Unix seconds), and whether it's saving the
 * resume state ([exiting]). A multi-disc game ([discCount] over 1) has its [disc] in the drive, and the Change disc
 * panel ([discs]) when it's open. The Patch codes panel is [codes]. In [hardcore] mode, Load state, Patch codes and
 * Fast forward are off.
 */
data class GameMenuState(
    val booted: Boolean = false,
    val fastForward: Boolean = false,
    val analogMode: Boolean? = null,
    val slots: SlotPanel? = null,
    val confirmingReset: Boolean = false,
    val exiting: Boolean = false,
    val cardBusy: Boolean = false,
    val confirmingExit: Boolean = false,
    val replacingResume: Long? = null,
    val disc: Int = 0,
    val discCount: Int = 0,
    val discs: DiscChoice? = null,
    val codes: CodePanel? = null,
    /** Hardcore mode is active: no state loads, patch codes or fast forward. */
    val hardcore: Boolean = false,
    /** The game's RetroAchievements, once read. */
    val achievements: GameAchievements? = null,
    /** The Achievements panel, while it's open. */
    val cheevos: AchievementsPanelState? = null,
    /** The game's own settings show in the menu's place. */
    val settings: Boolean = false,
    /** Back from the game's settings: the menu focuses its Settings row. */
    val cameFromSettings: Boolean = false,
    /** The game has a key to keep its own settings under; a PSF has none. */
    val hasKey: Boolean = true,
    /** The core has a load to undo in this game (never in hardcore mode): Undo load is on. */
    val canUndo: Boolean = false,
)

/** What the in-game menu's rows and panels do. */
interface GameMenuActions {
    fun resume()

    fun openSlots(mode: SlotMode)

    fun closeSlots()

    fun pickSlot(slot: Int)

    fun openDiscs()

    fun closeDiscs()

    /** Puts disc [number] in, or just closes the panel for the disc already in. */
    fun pickDisc(number: Int)

    fun openCodes()

    fun closeCodes()

    /** Switches code [index] on or off, at once; the menu stays open. */
    fun setCode(
        index: Int,
        on: Boolean,
    )

    /** Applies manual code [index] once; the menu stays open. */
    fun applyCode(index: Int)

    /** Loads one of [SlotPanel.others]: [serial]'s [slot]. */
    fun pickOther(
        serial: String,
        slot: Int,
    )

    fun confirmReplace()

    fun cancelReplace()

    fun toggleFastForward()

    fun toggleAnalogMode()

    fun screenshot()

    fun askReset()

    fun confirmReset()

    fun cancelReset()

    fun exit()

    fun confirmExit()

    fun cancelExit()

    fun replaceResume()

    fun keepResume()

    fun cancelReplaceResume()

    /** Opens the Achievements panel, if the game's set is ready. */
    fun openAchievements() {}

    fun closeAchievements() {}

    /** L1 or R1: the open panel's other tab, when it has two: the slot panel's, or the Achievements panel's. */
    fun switchTab() {}

    /** Saves to or loads shared slot [slot], as the slot panel's mode says. */
    fun pickShared(slot: Int) {}

    /** X or a long press on a Load row: asks before deleting [entry]'s save. */
    fun askDelete(entry: SlotEntry) {}

    /** Deletes the save asked about, staying in the panel. */
    fun confirmDelete() {}

    fun cancelDelete() {}

    /** Opens leaderboard [id] in the Leaderboards tab, fetching its entries. */
    fun openLeaderboard(id: Int) {}

    /** Back from an open leaderboard to the tab's list. */
    fun closeLeaderboard() {}

    /** Undoes the last state load, as the core keeps it, and goes back to the game. */
    fun undoLoad() {}

    /** Opens the running game's own settings, in the menu's place, once it has booted. */
    fun openSettings() {}

    /** Back from the game's settings to the menu. */
    fun closeSettings() {}
}

/** The save state hotkeys the app runs itself, by the core's names for them ([coreName]). */
enum class StateHotkey(
    val coreName: String,
) {
    Save("SaveSelectedSaveState"),
    Load("LoadSelectedSaveState"),
    Previous("SelectPreviousSaveStateSlot"),
    Next("SelectNextSaveStateSlot"),
    SaveAndNext("SaveStateAndSelectNextSlot"),
    Undo("UndoLoadState"),
}

/**
 * The in-game menu's state and actions, from a game's boot to its end. Main thread only. Its [api] and [lastSlots] calls
 * run on [game], one at a time, never on the main thread. A save or load that works says so ([onMessage]) and goes back
 * to the game ([onResume]); one that fails stays in the menu, saying why. Every other action goes back to the game.
 */
class InGameMenu(
    private val scope: CoroutineScope,
    private val game: CoroutineDispatcher,
    private val api: GameCoreApi,
    private val lastSlots: LastSlotStore,
    private val records: GameRecordStore,
    private val onMessage: (String) -> Unit,
    private val onResume: () -> Unit,
    private val onExit: (resumeError: String?) -> Unit,
    /** The running game's own settings are to show, or, with null, to go. */
    private val onSettings: (GameEntry?) -> Unit = {},
) : GameMenuActions {
    var state by mutableStateOf(GameMenuState())
        private set

    private val reader = SaveReader(api, game)

    /** The running game, once it has booted. */
    private var running: GameEntry? = null

    /** The running game's key, once it has booted; blank for a game without one, which has no save states. */
    private val serial: String? get() = running?.serial

    /** Changes as each game starts and stops, so a read for an earlier one is dropped. */
    private var gameId = 0

    /** Changes as the slot panel opens and closes, so a read for an earlier panel is dropped. */
    private var panelId = 0

    /** Changes as a leaderboard opens or closes, so the core's answer for an earlier one is dropped. */
    private var leaderboardRequest = 0

    private val canSave get() = state.booted && !serial.isNullOrBlank()

    /** The save state hotkeys' slot, once one has run in this game: until then, the last slot, or slot 1. */
    private var selectedSlot: Int? = null

    /**
     * [game] booted: its key ([GameEntry.serial], blank for none) keeps its save states. Fast forward is off, and the
     * analog mode is read.
     */
    fun gameStarted(game: GameEntry) {
        gameId++
        panelId++
        running = game
        selectedSlot = null
        state = GameMenuState(booted = true, discCount = game.discs.size, hasKey = game.serial.isNotBlank())
        readGameState()
    }

    /** The game is gone. */
    fun gameStopped() {
        gameId++
        panelId++
        running = null
        selectedSlot = null
        if (state.settings) onSettings(null)
        state = GameMenuState()
    }

    /**
     * The menu opens: on its rows, with nothing asked, and the analog mode and whether a memory card is being written
     * read again, as the game changes them. The paused game can't change them while it shows.
     */
    fun opened() {
        panelId++
        state =
            state.copy(
                slots = null,
                discs = null,
                codes = null,
                cheevos = null,
                confirmingReset = false,
                confirmingExit = false,
                replacingResume = null,
                settings = false,
                cameFromSettings = false,
            )
        if (state.booted) readGameState()
    }

    /** The sign-in, the game's achievements or hardcore mode changed: read them again, and the open tab's list. */
    fun achievementsChanged() {
        if (!state.booted) return
        readGameState()
        if (state.cheevos?.tab == CheevoTab.Achievements) readSections()
    }

    override fun openAchievements() {
        val game = state.achievements
        if (!state.booted || game == null || game.status != GameAchievements.READY) return
        panelId++
        val tab = if (game.hasAchievements) CheevoTab.Achievements else CheevoTab.Leaderboards
        state =
            state.copy(
                slots = null,
                discs = null,
                codes = null,
                cheevos = AchievementsPanelState(tab),
                confirmingReset = false,
            )
        readTab(tab)
    }

    override fun closeAchievements() {
        panelId++
        state = state.copy(cheevos = null)
    }

    override fun switchTab() {
        state.slots?.let { slots ->
            if (slots.busy || slots.replacing != null || slots.deleting != null) return
            val tab = if (slots.tab == SlotTab.ThisGame) SlotTab.Shared else SlotTab.ThisGame
            state = state.copy(slots = slots.copy(tab = tab, error = null))
            return
        }
        val panel = state.cheevos ?: return
        val game = state.achievements ?: return
        if (!game.hasAchievements || !game.hasLeaderboards) return
        panelId++
        val tab = if (panel.tab == CheevoTab.Achievements) CheevoTab.Leaderboards else CheevoTab.Achievements
        // An open leaderboard closes; a reply for it that comes later is dropped.
        state = state.copy(cheevos = AchievementsPanelState(tab, panel.sections, panel.leaderboards))
        readTab(tab)
    }

    override fun openLeaderboard(id: Int) {
        val panel = state.cheevos ?: return
        val board = panel.leaderboards?.firstOrNull { it.id == id } ?: return
        val request = ++leaderboardRequest
        state = state.copy(cheevos = panel.copy(open = board, page = null, pageError = null, request = request))
        scope.launch(game) { api.fetchLeaderboard(id, request) }
    }

    override fun closeLeaderboard() {
        val panel = state.cheevos ?: return
        leaderboardRequest++
        state = state.copy(cheevos = panel.copy(open = null, page = null, pageError = null))
    }

    /** The core's answer to [request]: a leaderboard's [page], or why there's none. Dropped unless it's still open. */
    fun leaderboardFetched(
        request: Int,
        page: LeaderboardPage?,
        error: String?,
    ) {
        val panel = state.cheevos ?: return
        if (panel.open == null || request != leaderboardRequest || panel.request != request) return
        state = state.copy(cheevos = panel.copy(page = page, pageError = if (page == null) error.orEmpty() else null))
    }

    private fun readTab(tab: CheevoTab) {
        if (tab == CheevoTab.Achievements) readSections() else readLeaderboards()
    }

    /** Reads the Leaderboards tab's list, for the panel open now. */
    private fun readLeaderboards() {
        val id = panelId
        scope.launch {
            val boards = withContext(game) { api.getLeaderboards() }
            val panel = state.cheevos
            if (id != panelId || panel == null) return@launch
            state = state.copy(cheevos = panel.copy(leaderboards = boards))
        }
    }

    /** Reads the Achievements tab's sections, for the panel open now. */
    private fun readSections() {
        val id = panelId
        scope.launch {
            val sections = withContext(game) { AchievementLists.sections(api.getAchievementSections()) }
            val panel = state.cheevos
            if (id != panelId || panel == null) return@launch
            state = state.copy(cheevos = panel.copy(sections = sections))
        }
    }

    /** Fast forward was turned on or off, by the menu or a hotkey. */
    fun fastForwardChanged(enabled: Boolean) {
        state = state.copy(fastForward = enabled)
    }

    /** Back to the game, unless Exit game is saving the resume state: the game stops once that's done. */
    override fun resume() {
        if (state.exiting) return
        if (state.settings) {
            state = state.copy(settings = false)
            onSettings(null)
        }
        onResume()
    }

    override fun openSettings() {
        val game = running ?: return
        if (!state.booted || game.serial.isBlank()) return
        panelId++
        state =
            state.copy(
                slots = null,
                discs = null,
                codes = null,
                cheevos = null,
                settings = true,
                cameFromSettings = false,
            )
        onSettings(game)
    }

    override fun closeSettings() {
        if (!state.settings) return
        state = state.copy(settings = false, cameFromSettings = true)
        onSettings(null)
    }

    /**
     * Exit game: a game that has booted, with an ID (its serial, or the core's content hash for a PS-EXE or a disc the
     * database doesn't know), saves its resume state first, on [game], then the game stops ([onExit], with the reason
     * if the save failed: the game stops all the same). One that has a resume state already asks first
     * ([replacingResume]). Before the boot, or with `SaveStateOnExit` off, nothing is saved.
     */
    override fun exit() {
        val serial = serial
        if (state.exiting || state.replacingResume != null) return
        if (state.cardBusy && !state.confirmingExit) {
            state = state.copy(confirmingExit = true)
            return
        }
        state = state.copy(confirmingExit = false)
        if (!canSave || serial == null) return onExit(null)
        state = state.copy(exiting = true)
        scope.launch {
            if (!withContext(game) { api.savesResumeState() }) return@launch onExit(null)
            val savedAt = withContext(game) { api.getSaveStateTimes(serial)[0] }
            if (savedAt > 0) {
                state = state.copy(exiting = false, replacingResume = savedAt)
            } else {
                saveAndExit()
            }
        }
    }

    /** Exit game, replacing the resume state. */
    override fun replaceResume() {
        if (state.replacingResume == null) return
        state = state.copy(replacingResume = null, exiting = true)
        scope.launch { saveAndExit() }
    }

    /** Exit game, keeping the resume state as it is. */
    override fun keepResume() {
        if (state.replacingResume == null) return
        state = state.copy(replacingResume = null)
        onExit(null)
    }

    /** Stay in the game. */
    override fun cancelReplaceResume() {
        state = state.copy(replacingResume = null)
    }

    private suspend fun saveAndExit() {
        val key = serial.orEmpty()
        val error = withContext(game) { api.saveResumeState(key) }
        onExit(error)
    }

    override fun openSlots(mode: SlotMode) {
        val serial = serial
        if (!state.booted || serial == null || (mode == SlotMode.Load && state.hardcore)) return
        val id = ++panelId
        val hasKey = serial.isNotBlank()
        val tab = if (hasKey) SlotTab.ThisGame else SlotTab.Shared
        state =
            state.copy(
                slots = SlotPanel(mode, entries = if (hasKey) null else emptyList(), tab = tab, hasKey = hasKey),
                discs = null,
                codes = null,
                confirmingReset = false,
            )
        readSlots(id, mode)
    }

    /**
     * Reads slot panel [id]'s lists, the game's own, its other discs' and the shared ones, then each saved row's picture
     * (and a shared slot's game), the open tab's first. With [focusRow], that row is to take the focus.
     */
    private fun readSlots(
        id: Int,
        mode: SlotMode,
        focusRow: SlotEntry? = null,
    ) {
        val serial = serial ?: return
        val hasKey = serial.isNotBlank()
        val discs = running?.discs.orEmpty()
        val multiDisc = discs.size > 1
        val path = running?.path.orEmpty()
        scope.launch {
            val entries = if (hasKey) reader.own(serial, mode) else emptyList()
            val last = if (hasKey) withContext(game) { lastSlots.get(serial) } else null
            // In Load, the states still filed under the game's other discs, as the migration left them or desktop saved them.
            val others = if (hasKey && mode == SlotMode.Load) reader.others(serial, discs) else emptyList()
            val shared = reader.shared(mode)
            if (id != panelId) return@launch
            updatePanel(id) {
                it.copy(
                    entries = entries,
                    focus = SaveLists.focus(mode, entries, last),
                    others = others,
                    shared = shared,
                    sharedFocus = SaveLists.focus(mode, shared, null),
                    focusRow = focusRow,
                )
            }
            val own = entries.filter { it.savedAt != null } + others
            val sharedSaved = shared.filter { it.savedAt != null }
            val tab = state.slots?.tab ?: SlotTab.ThisGame
            for (entry in if (tab == SlotTab.Shared) sharedSaved + own else own + sharedSaved) {
                val read = reader.preview(entry, serial, path, multiDisc) ?: continue
                if (id != panelId) return@launch
                updatePanel(id) { panel -> panel.withRow(read) }
            }
        }
    }

    override fun askDelete(entry: SlotEntry) {
        val panel = state.slots ?: return
        val key = serial ?: return
        if (panel.mode != SlotMode.Load || panel.busy || panel.replacing != null || panel.deleting != null) return
        val rows = panel.entries.orEmpty() + panel.others + panel.shared.orEmpty()
        if (rows.none { it.ref(key) == entry.ref(key) && it.savedAt != null }) return
        state = state.copy(slots = panel.copy(deleting = entry, error = null))
    }

    override fun cancelDelete() {
        val panel = state.slots ?: return
        state = state.copy(slots = panel.copy(deleting = null, focusRow = panel.deleting))
    }

    /**
     * Deletes the save asked about, by the state the question named (not whichever row has the focus now), then reads
     * the lists again; the row after it in its list takes the focus, else the one before.
     */
    override fun confirmDelete() {
        val panel = state.slots ?: return
        val entry = panel.deleting ?: return
        val key = serial ?: return
        val id = panelId
        val ref = entry.ref(key)
        val list =
            when {
                entry.shared -> panel.shared.orEmpty()
                entry.owner != null -> panel.others
                else -> panel.entries.orEmpty()
            }.filter { it.savedAt != null }
        val at = list.indexOfFirst { it.ref(key) == ref }
        val next = list.getOrNull(at + 1) ?: list.getOrNull(at - 1)
        updatePanel(id) { it.copy(deleting = null, busy = true, error = null) }
        scope.launch {
            val error = withContext(game) { api.deleteState(ref) }
            if (id != panelId) return@launch
            if (error != null) {
                updatePanel(id) { it.copy(busy = false, error = "Couldn't delete that save: $error", focusRow = entry) }
                return@launch
            }
            updatePanel(id) { it.copy(busy = false) }
            readSlots(id, panel.mode, next)
        }
    }

    override fun closeSlots() {
        panelId++
        state = state.copy(slots = null)
    }

    override fun pickSlot(slot: Int) {
        val panel = state.slots ?: return
        if (panel.busy || panel.replacing != null || panel.deleting != null || panel.tab != SlotTab.ThisGame ||
            !panel.hasKey
        ) {
            return
        }
        val entry = panel.entries?.firstOrNull { it.slot == slot } ?: return
        val saved = entry.savedAt != null
        when {
            panel.mode == SlotMode.Load -> if (saved) load(slot)
            saved -> state = state.copy(slots = panel.copy(replacing = slot))
            else -> save(slot)
        }
    }

    override fun pickShared(slot: Int) {
        val panel = state.slots ?: return
        if (panel.busy || panel.replacing != null || panel.deleting != null || panel.tab != SlotTab.Shared) return
        val entry = panel.shared?.firstOrNull { it.slot == slot } ?: return
        val saved = entry.savedAt != null
        when {
            panel.mode == SlotMode.Save ->
                if (saved) {
                    state =
                        state.copy(
                            slots = panel.copy(replacing = slot),
                        )
                } else {
                    saveShared(slot)
                }
            saved -> loadShared(entry)
        }
    }

    /**
     * Loads shared [entry], if it's the running game's. Its game is read first if the panel hasn't read it yet: a shared
     * slot is never loaded as this game's without knowing it is.
     */
    private fun loadShared(entry: SlotEntry) {
        val id = panelId
        val key = serial.orEmpty()
        val path = running?.path.orEmpty()
        scope.launch {
            val read = if (entry.holds != null) entry else reader.preview(entry, key, path, state.discCount > 1)
            if (id != panelId) return@launch
            read?.let { updatePanel(id) { panel -> panel.withRow(it) } }
            val holds = read?.holds
            when {
                holds == null -> updatePanel(id) { it.copy(error = UNREADABLE) }
                holds.path == path -> {
                    val slot = entry.slot
                    run(
                        slot,
                        { api.loadSharedState(slot) },
                        "Loaded shared slot $slot",
                        remember = false,
                        loads = true,
                    ) { "Couldn't load shared slot $slot: $it" }
                }
            }
        }
    }

    private fun saveShared(slot: Int) =
        run(slot, { api.saveSharedState(slot) }, "Saved to shared slot $slot", remember = false) {
            if (it == CARD_BUSY) CARD_BUSY_SAVE else "Couldn't save to shared slot $slot: $it"
        }

    override fun pickOther(
        serial: String,
        slot: Int,
    ) {
        val panel = state.slots ?: return
        if (panel.busy || panel.deleting != null || panel.others.none { it.owner == serial && it.slot == slot }) return
        val disc = running?.discs?.firstOrNull { it.serial == serial }?.number
        val what = if (slot == SaveSlots.RESUME) "resume state" else "slot $slot"
        run(
            slot,
            { api.loadState(serial, slot) },
            if (disc != null) "Loaded disc $disc's $what" else "Loaded that save",
            remember = false,
            loads = true,
        ) { "Couldn't load that save: $it" }
    }

    override fun openDiscs() {
        val discs = running?.discs.orEmpty()
        if (!state.booted || discs.size < 2) return
        panelId++
        state = state.copy(slots = null, codes = null, discs = DiscChoice(discs, state.disc), confirmingReset = false)
    }

    override fun closeDiscs() {
        state = state.copy(discs = null)
    }

    override fun pickDisc(number: Int) {
        val choice = state.discs ?: return
        val key = serial ?: return
        if (choice.busy) return
        if (number == choice.current) return closeDiscs()
        state = state.copy(discs = choice.copy(busy = true, error = null))
        scope.launch {
            val error =
                withContext(game) {
                    api.changeDisc(number).also { if (it == null && key.isNotBlank()) records.setLastDisc(key, number) }
                }
            val open = state.discs ?: return@launch
            if (error != null) {
                state = state.copy(discs = open.copy(busy = false, error = error))
                return@launch
            }
            state = state.copy(disc = number, discs = null)
            onMessage("Disc $number inserted")
            onResume()
        }
    }

    override fun openCodes() {
        if (!state.booted || state.hardcore) return
        val id = ++panelId
        state = state.copy(slots = null, discs = null, codes = CodePanel(), confirmingReset = false)
        scope.launch {
            val codes = withContext(game) { api.getPatchCodes() }
            if (id != panelId) return@launch
            val panel = state.codes ?: return@launch
            state =
                state.copy(
                    codes = panel.copy(groups = PatchCodeList.groups(codes.codes.toList()), fileName = codes.fileName),
                )
        }
    }

    override fun closeCodes() {
        panelId++
        state = state.copy(codes = null)
    }

    override fun setCode(
        index: Int,
        on: Boolean,
    ) {
        val name = codeRow(index)?.name ?: return
        changeCode(index, on)
        val id = panelId
        scope.launch {
            val error = withContext(game) { api.setPatchCode(index, on) } ?: return@launch
            if (id != panelId) return@launch
            changeCode(index, !on)
            state = state.copy(codes = state.codes?.copy(error = "Couldn't switch $name: $error", applied = null))
        }
    }

    override fun applyCode(index: Int) {
        val name = codeRow(index)?.name ?: return
        val id = panelId
        scope.launch {
            val error = withContext(game) { api.applyPatchCode(index) }
            if (id != panelId) return@launch
            val panel = state.codes ?: return@launch
            state =
                state.copy(
                    codes =
                        if (error == null) {
                            panel.copy(applied = "Applied: $name", error = null)
                        } else {
                            panel.copy(error = "Couldn't apply $name: $error", applied = null)
                        },
                )
        }
    }

    private fun codeRow(index: Int) =
        state.codes
            ?.groups
            ?.flatMap { it.codes }
            ?.firstOrNull { it.index == index }

    // Shows code [index] as [on], at once.
    private fun changeCode(
        index: Int,
        on: Boolean,
    ) {
        val panel = state.codes ?: return
        val groups =
            panel.groups?.map { group ->
                group.copy(codes = group.codes.map { if (it.index == index) it.copy(on = on) else it })
            }
        state = state.copy(codes = panel.copy(groups = groups, error = null))
    }

    override fun confirmReplace() {
        val panel = state.slots ?: return
        val slot = panel.replacing ?: return
        state = state.copy(slots = panel.copy(replacing = null))
        if (panel.tab == SlotTab.Shared) saveShared(slot) else save(slot)
    }

    override fun cancelReplace() {
        val panel = state.slots ?: return
        state = state.copy(slots = panel.copy(replacing = null))
    }

    override fun toggleFastForward() {
        if (!state.booted || state.hardcore) return
        val enabled = !state.fastForward
        // At once: the core's report comes once the game runs again.
        state = state.copy(fastForward = enabled)
        thenResume { api.setFastForward(enabled) }
    }

    override fun toggleAnalogMode() {
        if (state.booted) thenResume { api.toggleAnalogMode() }
    }

    override fun screenshot() {
        if (state.booted) thenResume { api.saveScreenshot() }
    }

    override fun askReset() {
        if (state.booted) state = state.copy(confirmingReset = true)
    }

    override fun confirmReset() {
        if (!state.confirmingReset) return
        state = state.copy(confirmingReset = false)
        thenResume { api.resetGame() }
    }

    override fun cancelReset() {
        state = state.copy(confirmingReset = false)
    }

    /** Exit game, though the game is writing a memory card. */
    override fun confirmExit() {
        if (state.confirmingExit) exit()
    }

    override fun cancelExit() {
        state = state.copy(confirmingExit = false)
    }

    private fun save(slot: Int) =
        run(slot, { api.saveState(it, slot) }, "Saved to slot $slot") {
            // The paused game can't finish its card write: it has to run for a moment first.
            if (it == CARD_BUSY) CARD_BUSY_SAVE else "Couldn't save to slot $slot: $it"
        }

    private fun load(slot: Int) {
        val resume = slot == SaveSlots.RESUME
        run(
            slot,
            { api.loadState(it, slot) },
            if (resume) "Loaded your resume state" else "Loaded slot $slot",
            loads = true,
        ) { if (resume) "Couldn't load your resume state: $it" else "Couldn't load slot $slot: $it" }
    }

    /**
     * Runs [call] for [slot] on [game]. When it works, the slot is remembered as the last one (unless not [remember]),
     * the panel closes, and the game goes on with [done] shown; when it fails, the panel stays, with [failed] of the
     * reason. A load also reads which disc is in now.
     */
    private fun run(
        slot: Int,
        call: (key: String) -> String?,
        done: String,
        remember: Boolean = true,
        loads: Boolean = false,
        failed: (String) -> String,
    ) {
        val serial = serial ?: return
        val id = panelId
        val multiDisc = state.discCount > 1
        var disc = 0
        updatePanel(id) { it.copy(busy = true, error = null) }
        scope.launch {
            val error =
                withContext(game) {
                    call(serial).also {
                        if (it != null) return@also
                        if (remember) lastSlots.set(serial, slot)
                        // A load can put another disc in: it's the one played now.
                        if (loads && multiDisc) disc = readDisc(serial)
                    }
                }
            if (disc > 0) state = state.copy(disc = disc)
            if (error != null) {
                updatePanel(id) { it.copy(busy = false, error = failed(error)) }
                return@launch
            }
            // The save state hotkeys' slot follows the menu's too.
            if (remember) selectedSlot = slot
            if (id == panelId) closeSlots()
            onMessage(done)
            onResume()
        }
    }

    /** Runs [call] on [game], then goes back to the game, after it, so the core runs the two in that order. */
    private fun thenResume(call: () -> Unit) {
        scope.launch {
            withContext(game) { call() }
            onResume()
        }
    }

    private fun readGameState() {
        val id = gameId
        val key = serial.orEmpty()
        val multiDisc = state.discCount > 1
        scope.launch {
            val (mode, cardBusy) = withContext(game) { api.getAnalogMode() to api.isSavingMemoryCards() }
            val (hardcore, achievements) = withContext(game) { api.isHardcoreActive() to api.getGameAchievements() }
            val canUndo = withContext(game) { api.canUndoLoadState() }
            if (id == gameId) {
                state =
                    state.copy(
                        analogMode = if (mode < 0) null else mode == 1,
                        cardBusy = cardBusy,
                        hardcore = hardcore,
                        achievements = achievements,
                        canUndo = canUndo && !hardcore,
                    )
            }
            if (!multiDisc) return@launch
            val disc = withContext(game) { readDisc(key) }
            if (id == gameId && disc > 0) state = state.copy(disc = disc)
        }
    }

    /**
     * A save state hotkey, while the game runs: it saves, loads or picks the slot the menu's way (the game's key, the
     * last slot, the disc read after a load, the same messages), without opening the menu or resuming. Load is refused
     * in hardcore mode, and a game without a key keeps no states.
     */
    fun hotkey(which: StateHotkey) {
        if (!state.booted) return
        val key = serial
        if (key.isNullOrBlank()) return onMessage(NO_STATES)
        // An undo is a load too.
        if ((which == StateHotkey.Load || which == StateHotkey.Undo) && state.hardcore) return onMessage(HARDCORE_LOAD)
        val id = gameId
        scope.launch {
            val slot =
                selectedSlot
                    ?: withContext(game) { lastSlots.get(key) }?.takeIf { it in 1..SaveSlots.COUNT }
                    ?: 1
            if (id != gameId) return@launch
            when (which) {
                StateHotkey.Previous -> selectSlot(if (slot == 1) SaveSlots.COUNT else slot - 1)
                StateHotkey.Next -> selectSlot(slot % SaveSlots.COUNT + 1)
                StateHotkey.Save -> hotkeyState(id, key, slot, loads = false)
                StateHotkey.SaveAndNext ->
                    if (hotkeyState(id, key, slot, loads = false) && id == gameId) {
                        selectedSlot = slot % SaveSlots.COUNT + 1
                    }
                StateHotkey.Load -> hotkeyState(id, key, slot, loads = true)
                StateHotkey.Undo -> undo(id, key)
            }
        }
    }

    /** Undo load: refused in hardcore mode, as Load state is. */
    override fun undoLoad() {
        val key = serial ?: return
        if (!state.booted || state.hardcore || !state.canUndo) return
        val id = gameId
        scope.launch {
            undo(id, key)
            if (id == gameId) onResume()
        }
    }

    /** Undoes the last load, reading the disc it put back for a multi-disc game, and says how it went. */
    private suspend fun undo(
        id: Int,
        key: String,
    ) {
        val multiDisc = state.discCount > 1
        var disc = 0
        val undone = withContext(game) { api.undoLoadState().also { if (it && multiDisc) disc = readDisc(key) } }
        if (id != gameId) return
        state = state.copy(canUndo = false)
        if (disc > 0) state = state.copy(disc = disc)
        onMessage(if (undone) "Undid the last load" else "No load to undo")
    }

    private fun selectSlot(slot: Int) {
        selectedSlot = slot
        onMessage("Slot $slot selected")
    }

    /** Saves or [loads] [key]'s [slot] for a hotkey; says how it went, and returns whether it worked. */
    private suspend fun hotkeyState(
        id: Int,
        key: String,
        slot: Int,
        loads: Boolean,
    ): Boolean {
        val multiDisc = state.discCount > 1
        var disc = 0
        val error =
            withContext(game) {
                (if (loads) api.loadState(key, slot) else api.saveState(key, slot)).also {
                    if (it != null) return@also
                    lastSlots.set(key, slot)
                    if (loads && multiDisc) disc = readDisc(key)
                }
            }
        if (id != gameId) return false
        if (disc > 0) state = state.copy(disc = disc)
        if (error == null) selectedSlot = slot
        onMessage(
            when {
                error == null -> if (loads) "Loaded slot $slot" else "Saved to slot $slot"
                loads -> "Couldn't load slot $slot: $error"
                error == CARD_BUSY -> CARD_BUSY_SAVE
                else -> "Couldn't save to slot $slot: $error"
            },
        )
        return error == null
    }

    /** The disc in the drive (0 if unknown), kept as the game's last disc. Blocks: on [game]. */
    private fun readDisc(key: String): Int {
        val disc = api.getCurrentDisc()
        if (disc > 0 && key.isNotBlank()) records.setLastDisc(key, disc)
        return disc
    }

    // The panel with [read] in place of the row it was read for, whichever list has it.
    private fun SlotPanel.withRow(read: SlotEntry): SlotPanel {
        fun SlotEntry.with() = if (slot == read.slot && owner == read.owner && shared == read.shared) read else this
        return copy(
            entries = entries?.map { it.with() },
            others = others.map { it.with() },
            shared = shared?.map { it.with() },
        )
    }

    // Changes the slot panel if it's still panel [id].
    private fun updatePanel(
        id: Int,
        change: (SlotPanel) -> SlotPanel,
    ) {
        val panel = state.slots ?: return
        if (id == panelId) state = state.copy(slots = change(panel))
    }

    private companion object {
        /** The core's reason for refusing a save while the game writes a memory card, and what the app says instead. */
        const val CARD_BUSY = "Cannot save state while memory card is being saved."
        const val CARD_BUSY_SAVE =
            "The game is still saving to its memory card. Resume it for a moment, then save again."

        /** What the save state hotkeys say for a game without a key, and for Load in hardcore mode. */
        const val NO_STATES = "This game can't keep save states"
        const val HARDCORE_LOAD = "Loading a state is off in hardcore mode"

        /** A shared slot whose state can't be read: nothing says which game it holds. */
        const val UNREADABLE = "Couldn't read that save."
    }
}
