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
import kotlinx.coroutines.Job
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
 * What the slot panel asks before it acts: to replace a saved slot ([Replace], in the open tab), or, for a shared slot of
 * another game, whether to stop this game and start that one from it ([Switch]).
 */
sealed interface SlotQuestion {
    data class Replace(
        val slot: Int,
    ) : SlotQuestion

    data class Switch(
        val entry: SlotEntry,
    ) : SlotQuestion
}

/** What the slot panel has under way: a save, or a load (with a shared slot's game read first). */
enum class SlotWork { Saving, Loading }

/**
 * The slot panel: its [lists] (the game's own slots, its other discs' saves in Load, and the shared slots, with the
 * delete and the error line), the [question] it asks, if any, and the [work] under way, if any.
 */
data class SlotPanel(
    val lists: SaveListState,
    val question: SlotQuestion? = null,
    val work: SlotWork? = null,
) {
    val mode: SlotMode get() = lists.mode

    /** A save, load or delete is under way: the panel stays until it's done. */
    val working: Boolean get() = work != null || lists.removing

    /** Nothing asked, nothing under way: a pick, a delete or a tab switch may start. */
    val idle: Boolean get() = question == null && work == null && lists.idle
}

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
    /** Hardcore mode is active: no state loads, Undo load, patch codes or fast forward. */
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

    /** Loads the other game's shared slot asked about: this game is left as Exit game leaves it, then that one starts. */
    fun confirmSwitch() {}

    fun cancelSwitch() {}

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

/** A game to start once the running one has stopped: the library game at [gamePath], from [state]. */
data class StateStart(
    val gamePath: String,
    val state: StateRef,
)

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
 * to the game ([onResume]); one that fails stays in the menu, saying why. So do a disc change and the toggles; the
 * panels (discs, codes, achievements) and a delete stay in the menu.
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
    /** Exit game is leaving for another game, to start from a shared slot: just before [onExit]. */
    private val onSwitch: (StateStart) -> Unit = {},
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

    /** The slot panel's lists: open while the panel is, which shows each change. */
    private val slotLists =
        SaveListModel(scope, game, reader, api) { lists ->
            state = state.copy(slots = lists?.let { state.slots?.copy(lists = it) ?: SlotPanel(it) })
        }

    /** The Patch codes panel: open while the menu shows it, which shows each change. */
    private val codes = PatchCodesModel(scope, game, api) { state = state.copy(codes = it) }

    /** The Achievements panel: open while the menu shows it, which shows each change. */
    private val cheevos = AchievementsPanelModel(scope, game, api) { state = state.copy(cheevos = it) }

    private val canSave get() = state.booted && !serial.isNullOrBlank()

    /** The game Exit game is leaving for, once the player has said to load another game's shared slot. */
    private var switchTo: StateStart? = null

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
        closeModels()
        state = GameMenuState(booted = true, discCount = game.discs.size, hasKey = game.serial.isNotBlank())
        readGameState()
    }

    /** The game is gone. */
    fun gameStopped() {
        gameId++
        panelId++
        running = null
        switchTo = null
        selectedSlot = null
        if (state.settings) onSettings(null)
        closeModels()
        state = GameMenuState()
    }

    /**
     * The menu opens: on its rows, with nothing asked, and the analog mode and whether a memory card is being written
     * read again, as the game changes them. The paused game can't change them while it shows.
     */
    fun opened() {
        panelId++
        switchTo = null
        closeModels()
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
        cheevos.achievementsChanged()
    }

    override fun openAchievements() {
        val game = state.achievements
        if (!state.booted || game == null || game.status != GameAchievements.READY) return
        panelId++
        val tab = if (game.hasAchievements) CheevoTab.Achievements else CheevoTab.Leaderboards
        state = withSide()
        cheevos.open(tab)
    }

    override fun closeAchievements() {
        panelId++
        cheevos.close()
    }

    override fun switchTab() {
        state.slots?.let { slots ->
            if (slots.idle) slotLists.switchTab()
            return
        }
        if (state.cheevos == null) return
        val game = state.achievements ?: return
        if (!game.hasAchievements || !game.hasLeaderboards) return
        panelId++
        cheevos.switchTab()
    }

    override fun openLeaderboard(id: Int) = cheevos.openLeaderboard(id)

    override fun closeLeaderboard() = cheevos.closeLeaderboard()

    /** The core's answer to [request]: a leaderboard's [page], or why there's none. Dropped unless it's still open. */
    fun leaderboardFetched(
        request: Int,
        page: LeaderboardPage?,
        error: String?,
    ) = cheevos.leaderboardFetched(request, page, error)

    /** Fast forward was turned on or off, by the menu or a hotkey. */
    fun fastForwardChanged(enabled: Boolean) {
        state = state.copy(fastForward = enabled)
    }

    /**
     * Back to the game, closing its settings, unless Exit game is saving the resume state: the game stops once that's
     * done. Every action that goes back to the game, a save or a disc change done, comes here.
     */
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
            withSide().copy(
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
        if (!canSave || serial == null) return leave(null)
        state = state.copy(exiting = true)
        scope.launch {
            if (!withContext(game) { api.savesResumeState() }) return@launch leave(null)
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
        leave(null)
    }

    /** Stay in the game, and not go to another. */
    override fun cancelReplaceResume() {
        switchTo = null
        state = state.copy(replacingResume = null)
    }

    private suspend fun saveAndExit() {
        val key = serial.orEmpty()
        val error = withContext(game) { api.saveResumeState(key) }
        leave(error)
    }

    /**
     * The game stops ([onExit]), saying first which game is to start after it, if one is ([onSwitch]). Not when its
     * resume state couldn't be saved: the library says so, rather than another game hiding it.
     */
    private fun leave(resumeError: String?) {
        if (resumeError == null) switchTo?.let(onSwitch)
        switchTo = null
        onExit(resumeError)
    }

    override fun openSlots(mode: SlotMode) {
        val serial = serial
        if (!state.booted || serial == null || (mode == SlotMode.Load && state.hardcore)) return
        state = withSide()
        panelId++
        val game = running
        slotLists.open(serial, game?.path.orEmpty(), game?.discs.orEmpty(), mode) { lastSlots.get(serial) }
    }

    override fun confirmSwitch() {
        val panel = state.slots ?: return
        val entry = (panel.question as? SlotQuestion.Switch)?.entry ?: return
        val path = entry.holds?.path ?: return
        switchTo = StateStart(path, entry.ref(serial.orEmpty()))
        dropSlots()
        exit()
    }

    override fun cancelSwitch() {
        val panel = state.slots ?: return
        val switching = (panel.question as? SlotQuestion.Switch)?.entry ?: return
        state = state.copy(slots = panel.copy(question = null))
        slotLists.focusOn(switching)
    }

    override fun askDelete(entry: SlotEntry) {
        if (state.slots?.idle == true) slotLists.askDelete(entry)
    }

    override fun cancelDelete() = slotLists.cancelDelete()

    override fun confirmDelete() = slotLists.confirmDelete()

    // A save, load or delete under way ends in its panel, which shows its progress, then how it went.
    override fun closeSlots() {
        if (state.slots?.working == true) return
        dropSlots()
    }

    private fun dropSlots() {
        panelId++
        slotLists.close()
    }

    override fun pickSlot(slot: Int) {
        val panel = state.slots ?: return
        if (!panel.idle || panel.lists.tab != SlotTab.ThisGame || !panel.lists.hasKey) return
        val entry = panel.lists.entries?.firstOrNull { it.slot == slot } ?: return
        val saved = entry.savedAt != null
        when {
            panel.mode == SlotMode.Load -> if (saved) load(slot)
            saved -> state = state.copy(slots = panel.copy(question = SlotQuestion.Replace(slot)))
            else -> save(slot)
        }
    }

    override fun pickShared(slot: Int) {
        val panel = state.slots ?: return
        if (!panel.idle || panel.lists.tab != SlotTab.Shared) return
        val entry = panel.lists.shared?.firstOrNull { it.slot == slot } ?: return
        val saved = entry.savedAt != null
        when {
            panel.mode == SlotMode.Load -> if (saved) loadShared(entry)
            saved -> state = state.copy(slots = panel.copy(question = SlotQuestion.Replace(slot)))
            else -> saveShared(slot)
        }
    }

    /**
     * Loads shared [entry], if it's the running game's. Its game is read first if the panel hasn't read it yet: a shared
     * slot is never loaded as this game's without knowing it is.
     */
    private fun loadShared(entry: SlotEntry) {
        val id = panelId
        val path = running?.path.orEmpty()
        // Busy at once, while its game is read: a second A starts nothing (a second load would take the undo state).
        updatePanel(id) { it.copy(work = SlotWork.Loading) }
        slotLists.clearError()
        scope.launch {
            val read = slotLists.readHolds(entry) ?: return@launch
            if (id != panelId) return@launch
            val holds = read.holds
            when {
                holds == null -> failed(id, SaveLists.UNREADABLE)
                holds.path == null -> failed(id, SaveLists.notInLibrary(read))
                holds.path != path -> {
                    // Asked before this game is left, so a refusal loses nothing.
                    if (withContext(game) { api.hardcoreWouldStart() }) {
                        failed(id, GameStarts.HARDCORE_LOAD)
                    } else {
                        updatePanel(id) { it.copy(work = null, question = SlotQuestion.Switch(read)) }
                    }
                }
                else -> {
                    val slot = entry.slot
                    run(
                        slot,
                        SlotWork.Loading,
                        { api.loadSharedState(slot) },
                        "Loaded shared slot $slot",
                        remember = false,
                        loads = true,
                    ) { "Couldn't load shared slot $slot: $it" }
                }
            }
        }
    }

    // Panel [id]'s work ended in [error], said in its line.
    private fun failed(
        id: Int,
        error: String,
    ) {
        if (id != panelId || state.slots == null) return
        updatePanel(id) { it.copy(work = null) }
        slotLists.fail(error)
    }

    private fun saveShared(slot: Int) =
        run(slot, SlotWork.Saving, { api.saveSharedState(slot) }, "Saved to shared slot $slot", remember = false) {
            if (it == CARD_BUSY) CARD_BUSY_SAVE else "Couldn't save to shared slot $slot: $it"
        }

    override fun pickOther(
        serial: String,
        slot: Int,
    ) {
        val panel = state.slots ?: return
        if (!panel.idle || panel.lists.others.none { it.owner == serial && it.slot == slot }) return
        val disc = running?.discs?.firstOrNull { it.serial == serial }?.number
        val what = if (slot == SaveSlots.RESUME) "resume state" else "slot $slot"
        run(
            slot,
            SlotWork.Loading,
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
        state = withSide(discs = DiscChoice(discs, state.disc))
    }

    // A disc going in ends in its panel, as a save does.
    override fun closeDiscs() {
        if (state.discs?.busy == true) return
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
            // The panel inserting it, unless another row has taken its place: the disc put in is known all the same.
            val open = state.discs?.takeIf { it.busy }
            if (error != null) {
                if (open != null) {
                    state = state.copy(discs = open.copy(busy = false, error = error))
                } else {
                    onMessage(error)
                }
                return@launch
            }
            state = state.copy(disc = number, discs = if (open != null) null else state.discs)
            onMessage("Disc $number inserted")
            resume()
        }
    }

    override fun openCodes() {
        if (!state.booted || state.hardcore) return
        panelId++
        state = withSide()
        codes.open()
    }

    override fun closeCodes() {
        panelId++
        codes.close()
    }

    override fun setCode(
        index: Int,
        on: Boolean,
    ) = codes.setCode(index, on)

    override fun applyCode(index: Int) = codes.applyCode(index)

    override fun confirmReplace() {
        val panel = state.slots ?: return
        val slot = (panel.question as? SlotQuestion.Replace)?.slot ?: return
        state = state.copy(slots = panel.copy(question = null))
        if (panel.lists.tab == SlotTab.Shared) saveShared(slot) else save(slot)
    }

    override fun cancelReplace() {
        val panel = state.slots ?: return
        if (panel.question !is SlotQuestion.Replace) return
        state = state.copy(slots = panel.copy(question = null))
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
        switchTo = null
        state = state.copy(confirmingExit = false)
    }

    private fun save(slot: Int) =
        run(slot, SlotWork.Saving, { api.saveState(it, slot) }, "Saved to slot $slot") {
            // The paused game can't finish its card write: it has to run for a moment first.
            if (it == CARD_BUSY) CARD_BUSY_SAVE else "Couldn't save to slot $slot: $it"
        }

    private fun load(slot: Int) {
        val resume = slot == SaveSlots.RESUME
        run(
            slot,
            SlotWork.Loading,
            { api.loadState(it, slot) },
            if (resume) "Loaded your resume state" else "Loaded slot $slot",
            loads = true,
        ) { if (resume) "Couldn't load your resume state: $it" else "Couldn't load slot $slot: $it" }
    }

    /**
     * Runs [call] for [slot] on [game], with the panel showing [work] meanwhile. When it works, the slot is remembered as the last one (unless not [remember]),
     * the panel closes, and the game goes on with [done] shown; when it fails, the panel stays, with [failed] of the
     * reason. A load also reads which disc is in now.
     */
    private fun run(
        slot: Int,
        work: SlotWork,
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
        updatePanel(id) { it.copy(work = work) }
        slotLists.clearError()
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
                // Said in the panel, or once another row has taken its place, as a message: never lost with it.
                val reason = failed(error)
                if (id == panelId && state.slots != null) {
                    failed(id, reason)
                } else {
                    onMessage(reason)
                }
                return@launch
            }
            // The save state hotkeys' slot follows the menu's too.
            if (remember) selectedSlot = slot
            if (id == panelId) dropSlots()
            onMessage(done)
            resume()
        }
    }

    /** Runs [call] on [game], then goes back to the game, after it, so the core runs the two in that order. */
    private fun thenResume(call: () -> Unit) {
        scope.launch {
            withContext(game) { call() }
            resume()
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
        if ((which == StateHotkey.Load || which == StateHotkey.Undo) &&
            state.hardcore
        ) {
            return onMessage(GameStarts.HARDCORE_LOAD)
        }
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
            if (id == gameId) resume()
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

    // The menu with the one side panel given, or none, and no reset question: the side panels take turns, so B, which
    // closes the one shown, never finds another under it.
    private fun withSide(discs: DiscChoice? = null): GameMenuState {
        closeModels()
        return state.copy(slots = null, discs = discs, codes = null, cheevos = null, confirmingReset = false)
    }

    // The panels' models, closed wherever the menu drops every panel.
    private fun closeModels() {
        slotLists.close()
        codes.close()
        cheevos.close()
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
    }
}
