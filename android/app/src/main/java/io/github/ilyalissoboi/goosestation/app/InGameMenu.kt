// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.GameDisc
import io.github.ilyalissoboi.goosestation.core.GameEntry
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
 * game only.
 */
data class SlotEntry(
    val slot: Int,
    val savedAt: Long?,
    val picture: ArgbImage? = null,
    val owner: String? = null,
    val disc: Int? = null,
)

/**
 * The slot panel: its [mode], its [entries] (null while they're read), the slot to [focus] as it opens (null for none),
 * the slot it asks to replace, if any ([replacing]), whether a save or load is under way ([busy]), and the last one's
 * failure, as shown ([error]). In Load, [others] are the states still filed under the game's other discs.
 */
data class SlotPanel(
    val mode: SlotMode,
    val entries: List<SlotEntry>? = null,
    val focus: Int? = null,
    val replacing: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val others: List<SlotEntry> = emptyList(),
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
 * panel ([discs]) when it's open. The Patch codes panel is [codes].
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
) : GameMenuActions {
    var state by mutableStateOf(GameMenuState())
        private set

    /** The running game, once it has booted. */
    private var running: GameEntry? = null

    /** The running game's key, once it has booted; blank for a game without one, which has no save states. */
    private val serial: String? get() = running?.serial

    /** Changes as each game starts and stops, so a read for an earlier one is dropped. */
    private var gameId = 0

    /** Changes as the slot panel opens and closes, so a read for an earlier panel is dropped. */
    private var panelId = 0

    private val canSave get() = state.booted && !serial.isNullOrBlank()

    /**
     * [game] booted: its key ([GameEntry.serial], blank for none) keeps its save states. Fast forward is off, and the
     * analog mode is read.
     */
    fun gameStarted(game: GameEntry) {
        gameId++
        panelId++
        running = game
        state = GameMenuState(booted = true, discCount = game.discs.size)
        readGameState()
    }

    /** The game is gone. */
    fun gameStopped() {
        gameId++
        panelId++
        running = null
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
                confirmingReset = false,
                confirmingExit = false,
                replacingResume = null,
            )
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
        if (!canSave || serial == null) return
        val id = ++panelId
        state = state.copy(slots = SlotPanel(mode), discs = null, codes = null, confirmingReset = false)
        val discs = running?.discs.orEmpty()
        val multiDisc = discs.size > 1
        // In Load, the states still filed under the game's other discs, as the migration left them or desktop saved them.
        val otherSerials =
            if (mode ==
                SlotMode.Load
            ) {
                discs.map { it.serial }.filter { it.isNotBlank() && it != serial }.distinct()
            } else {
                emptyList()
            }
        scope.launch {
            val (times, last, otherTimes) =
                withContext(game) {
                    Triple(
                        api.getSaveStateTimes(serial),
                        lastSlots.get(serial),
                        otherSerials.associateWith { api.getSaveStateTimes(it) },
                    )
                }
            if (id != panelId) return@launch
            val entries = entries(mode, times)
            val others =
                otherTimes.flatMap { (owner, ownerTimes) ->
                    val disc = discs.firstOrNull { it.serial == owner }?.number
                    entries(SlotMode.Load, ownerTimes).map { it.copy(owner = owner, disc = disc) }
                }
            updatePanel(id) { it.copy(entries = entries, focus = focus(mode, entries, last), others = others) }
            for (entry in entries.filter { it.savedAt != null } + others) {
                val preview =
                    withContext(game) { api.getSaveStatePreview(entry.owner ?: serial, entry.slot) } ?: continue
                if (id != panelId) return@launch
                val disc = preview.disc.takeIf { multiDisc && it > 0 }

                fun SlotEntry.withPreview() =
                    if (slot == entry.slot &&
                        owner == entry.owner
                    ) {
                        copy(picture = preview.picture, disc = disc ?: this.disc)
                    } else {
                        this
                    }
                updatePanel(id) { panel ->
                    panel.copy(
                        entries = panel.entries?.map { it.withPreview() },
                        others = panel.others.map { it.withPreview() },
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

    override fun pickOther(
        serial: String,
        slot: Int,
    ) {
        val panel = state.slots ?: return
        if (panel.busy || panel.others.none { it.owner == serial && it.slot == slot }) return
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
        if (!state.booted) return
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
            if (id == gameId) state = state.copy(analogMode = if (mode < 0) null else mode == 1, cardBusy = cardBusy)
            if (!multiDisc) return@launch
            val disc = withContext(game) { readDisc(key) }
            if (id == gameId && disc > 0) state = state.copy(disc = disc)
        }
    }

    /** The disc in the drive (0 if unknown), kept as the game's last disc. Blocks: on [game]. */
    private fun readDisc(key: String): Int {
        val disc = api.getCurrentDisc()
        if (disc > 0 && key.isNotBlank()) records.setLastDisc(key, disc)
        return disc
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
