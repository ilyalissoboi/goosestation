// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.ArgbImage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the slot panel is for: saving to a slot, or loading one. */
enum class SlotMode { Save, Load }

/**
 * One slot as the slot list shows it: its [slot] ([SaveSlots.RESUME] for the resume state), when it was saved (Unix
 * seconds; null for an empty slot), and its [picture], once it has been read.
 */
data class SlotEntry(
    val slot: Int,
    val savedAt: Long?,
    val picture: ArgbImage? = null,
)

/**
 * The slot panel: its [mode], its [entries] (null while they're read), the slot to [focus] as it opens (null for none),
 * the slot it asks to replace, if any ([replacing]), whether a save or load is under way ([busy]), and the last one's
 * failure, as shown ([error]).
 */
data class SlotPanel(
    val mode: SlotMode,
    val entries: List<SlotEntry>? = null,
    val focus: Int? = null,
    val replacing: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * The in-game menu: whether the game has [booted] (until then only Resume and Exit game work), whether fast forward is
 * on, Port 1's analog mode (null when it isn't a DualShock: the row is hidden), the slot panel, if it's open,
 * whether it asks before a reset or (while the game is writing a memory card, [cardBusy]) an exit, whether Exit game
 * asks before it replaces the resume state saved at [replacingResume] (Unix seconds), and whether it's saving the
 * resume state ([exiting]).
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
)

/** What the in-game menu's rows and panels do. */
interface GameMenuActions {
    fun resume()

    fun openSlots(mode: SlotMode)

    fun closeSlots()

    fun pickSlot(slot: Int)

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
    private val onMessage: (String) -> Unit,
    private val onResume: () -> Unit,
    private val onExit: (resumeError: String?) -> Unit,
) : GameMenuActions {
    var state by mutableStateOf(GameMenuState())
        private set

    /** The running game's serial, once it has booted; blank for a game without one, which has no save states. */
    private var serial: String? = null

    /** Changes as each game starts and stops, so a read for an earlier one is dropped. */
    private var gameId = 0

    /** Changes as the slot panel opens and closes, so a read for an earlier panel is dropped. */
    private var panelId = 0

    private val canSave get() = state.booted && !serial.isNullOrBlank()

    /** The game booted, with [serial] (blank for none). Fast forward is off, and the analog mode is read. */
    fun gameStarted(serial: String) {
        gameId++
        panelId++
        this.serial = serial
        state = GameMenuState(booted = true)
        readGameState()
    }

    /** The game is gone. */
    fun gameStopped() {
        gameId++
        panelId++
        serial = null
        state = GameMenuState()
    }

    /**
     * The menu opens: on its rows, with nothing asked, and the analog mode and whether a memory card is being written
     * read again, as the game changes them. The paused game can't change them while it shows.
     */
    fun opened() {
        panelId++
        state = state.copy(slots = null, confirmingReset = false, confirmingExit = false, replacingResume = null)
        if (state.booted) readGameState()
    }

    /** Fast forward was turned on or off, by the menu or a hotkey. */
    fun fastForwardChanged(enabled: Boolean) {
        state = state.copy(fastForward = enabled)
    }

    /** Back to the game, unless Exit game is saving the resume state: the game stops once that's done. */
    override fun resume() {
        if (!state.exiting) onResume()
    }

    /**
     * Exit game: a game that has booted, with a serial, saves its resume state first, on [game], then the game stops
     * ([onExit], with the reason if the save failed: the game stops all the same). One that has a resume state already
     * asks first ([replacingResume]). Before the boot, or with `SaveStateOnExit` off, nothing is saved.
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
        val error = withContext(game) { api.saveResumeState() }
        onExit(error)
    }

    override fun openSlots(mode: SlotMode) {
        val serial = serial
        if (!canSave || serial == null) return
        val id = ++panelId
        state = state.copy(slots = SlotPanel(mode), confirmingReset = false)
        scope.launch {
            val (times, last) = withContext(game) { api.getSaveStateTimes(serial) to lastSlots.get(serial) }
            if (id != panelId) return@launch
            val entries = entries(mode, times)
            updatePanel(id) { it.copy(entries = entries, focus = focus(mode, entries, last)) }
            for (entry in entries.filter { it.savedAt != null }) {
                val picture = withContext(game) { api.getSaveStateScreenshot(serial, entry.slot) } ?: continue
                if (id != panelId) return@launch
                updatePanel(id) { panel ->
                    panel.copy(
                        entries =
                            panel.entries?.map {
                                if (it.slot ==
                                    entry.slot
                                ) {
                                    it.copy(picture = picture)
                                } else {
                                    it
                                }
                            },
                    )
                }
            }
        }
    }

    override fun closeSlots() {
        panelId++
        state = state.copy(slots = null)
    }

    override fun pickSlot(slot: Int) {
        val panel = state.slots ?: return
        if (panel.busy || panel.replacing != null) return
        val entry = panel.entries?.firstOrNull { it.slot == slot } ?: return
        val saved = entry.savedAt != null
        when {
            panel.mode == SlotMode.Load -> if (saved) load(slot)
            saved -> state = state.copy(slots = panel.copy(replacing = slot))
            else -> save(slot)
        }
    }

    override fun confirmReplace() {
        val panel = state.slots ?: return
        val slot = panel.replacing ?: return
        state = state.copy(slots = panel.copy(replacing = null))
        save(slot)
    }

    override fun cancelReplace() {
        val panel = state.slots ?: return
        state = state.copy(slots = panel.copy(replacing = null))
    }

    override fun toggleFastForward() {
        if (!state.booted) return
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
        run(slot, { api.saveState(slot) }, "Saved to slot $slot") {
            // The paused game can't finish its card write: it has to run for a moment first.
            if (it == CARD_BUSY) CARD_BUSY_SAVE else "Couldn't save to slot $slot: $it"
        }

    private fun load(slot: Int) {
        val resume = slot == SaveSlots.RESUME
        run(
            slot,
            { api.loadState(slot) },
            if (resume) "Loaded your resume state" else "Loaded slot $slot",
        ) { if (resume) "Couldn't load your resume state: $it" else "Couldn't load slot $slot: $it" }
    }

    /**
     * Runs [call] for [slot] on [game]. When it works, the slot is remembered as the last one, the panel closes, and
     * the game goes on with [done] shown; when it fails, the panel stays, with [failed] of the reason.
     */
    private fun run(
        slot: Int,
        call: () -> String?,
        done: String,
        failed: (String) -> String,
    ) {
        val serial = serial ?: return
        val id = panelId
        updatePanel(id) { it.copy(busy = true, error = null) }
        scope.launch {
            val error =
                withContext(game) {
                    call().also { if (it == null) lastSlots.set(serial, slot) }
                }
            if (error != null) {
                updatePanel(id) { it.copy(busy = false, error = failed(error)) }
                return@launch
            }
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
        scope.launch {
            val (mode, cardBusy) = withContext(game) { api.getAnalogMode() to api.isSavingMemoryCards() }
            if (id == gameId) state = state.copy(analogMode = if (mode < 0) null else mode == 1, cardBusy = cardBusy)
        }
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

        /** Save lists slots 1 to 10; Load lists the saved ones, the resume state first, when there is one. */
        fun entries(
            mode: SlotMode,
            times: LongArray,
        ): List<SlotEntry> {
            val slots = (1..SaveSlots.COUNT).map { SlotEntry(it, times.getOrNull(it)?.takeIf { time -> time > 0 }) }
            if (mode == SlotMode.Save) return slots
            // Load lists what can be loaded: the saved slots, the resume state first.
            val resume = times.getOrNull(0)?.takeIf { it > 0 }?.let { SlotEntry(SaveSlots.RESUME, it) }
            return listOfNotNull(resume) + slots.filter { it.savedAt != null }
        }

        /**
         * The slot to focus: the [last] one used, if Save lists it or Load has a save there; else Slot 1 (Save) or the
         * newest save (Load), if any.
         */
        fun focus(
            mode: SlotMode,
            entries: List<SlotEntry>,
            last: Int?,
        ): Int? =
            when (mode) {
                SlotMode.Save -> last?.takeIf { it in 1..SaveSlots.COUNT } ?: 1
                SlotMode.Load ->
                    last?.takeIf { slot -> entries.any { it.slot == slot && it.savedAt != null } }
                        ?: entries.filter { it.savedAt != null }.maxByOrNull { it.savedAt!! }?.slot
            }
    }
}
