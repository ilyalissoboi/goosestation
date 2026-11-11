// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameDisc
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The save lists a game's Saves page or its slot panel shows, in [mode], on [tab]: the game's own [entries] (null while
 * they're read; none for a game without a key, [hasKey] false), in Load the states still filed under its other discs
 * ([others]), and the [shared] slots (null while they're read). [focus] and [sharedFocus] are the slots to focus as the
 * lists open, when the owner asked for them; [focusRow], when set, is the row to focus first (the one after a deleted
 * save). [deleting] is the save it asks to delete, [removing] says the delete is under way, and [error] is the last
 * failure, the list's or its owner's.
 */
data class SaveListState(
    val mode: SlotMode,
    val tab: SlotTab,
    val hasKey: Boolean,
    val entries: List<SlotEntry>? = null,
    val others: List<SlotEntry> = emptyList(),
    val shared: List<SlotEntry>? = null,
    val focus: Int? = null,
    val sharedFocus: Int? = null,
    val focusRow: SlotEntry? = null,
    val deleting: SlotEntry? = null,
    val removing: Boolean = false,
    val error: String? = null,
) {
    /** No delete asked or under way. */
    val idle: Boolean get() = deleting == null && !removing

    /** The open tab's rows: the shared slots, or the game's own (none without a key). */
    val shownEntries: List<SlotEntry>?
        get() =
            when {
                tab == SlotTab.Shared -> shared
                hasKey -> entries
                else -> emptyList()
            }

    /** The other discs' saves, under the game's own on This game. */
    val shownOthers: List<SlotEntry> get() = if (tab == SlotTab.Shared || !hasKey) emptyList() else others

    /** These lists with [read] in place of the row it was read for, whichever list has it. */
    fun withRow(read: SlotEntry): SaveListState =
        copy(
            entries = entries?.let { SaveLists.withRow(it, read) },
            others = SaveLists.withRow(others, read),
            shared = shared?.let { SaveLists.withRow(it, read) },
        )
}

/**
 * The save lists both the Saves page and the in-game slot panel show ([SaveListState]): their reads, their pictures,
 * a shared slot's game, and the delete. Main thread only; [reader] and [api] calls run on [game]. Each change goes to
 * [onChange], null once it's closed. A read for lists closed or opened again since is dropped; that counts this
 * model's own opens and closes only.
 */
class SaveListModel(
    private val scope: CoroutineScope,
    private val game: CoroutineDispatcher,
    private val reader: SaveReader,
    private val api: GameCoreApi,
    private val onChange: (SaveListState?) -> Unit,
) {
    /** The open lists, or null. */
    var state: SaveListState? = null
        private set

    // Changes as the lists open and close, so a read for earlier ones is dropped.
    private var generation = 0

    // The lists' read under way, and their pictures': one at a time, so a re-read never reads a picture twice.
    private var listRead: Job? = null

    // What the open lists are of.
    private var key = ""
    private var path = ""
    private var discs = emptyList<GameDisc>()
    private var lastSlot: (() -> Int?)? = null

    /**
     * Opens [key]'s lists in [mode] (on This game, or on Shared for a game without a key) and reads them, for the game
     * at [path] with [discs]. With [lastSlot] (called on [game]), [SaveListState.focus] and
     * [SaveListState.sharedFocus] say which slot to focus as they open ([SaveLists.focus]).
     */
    fun open(
        key: String,
        path: String,
        discs: List<GameDisc>,
        mode: SlotMode,
        lastSlot: (() -> Int?)? = null,
    ) {
        generation++
        this.key = key
        this.path = path
        this.discs = discs
        this.lastSlot = lastSlot
        val hasKey = key.isNotBlank()
        val tab = if (hasKey) SlotTab.ThisGame else SlotTab.Shared
        set(SaveListState(mode, tab, hasKey, entries = if (hasKey) null else emptyList()))
        read(generation)
    }

    fun close() {
        generation++
        listRead?.cancel()
        set(null)
    }

    /** The other tab, unless a delete is asked or under way. */
    fun switchTab() {
        val lists = state ?: return
        if (!lists.idle) return
        val tab = if (lists.tab == SlotTab.ThisGame) SlotTab.Shared else SlotTab.ThisGame
        set(lists.copy(tab = tab, error = null, focusRow = null))
    }

    /** Asks before deleting [entry]'s save: in Load, with nothing else asked or under way, for a save still listed. */
    fun askDelete(entry: SlotEntry) {
        val lists = state ?: return
        if (lists.mode != SlotMode.Load || !lists.idle || entry.savedAt == null) return
        // Only a save the lists still have: one a re-read has dropped is gone already.
        val rows = lists.entries.orEmpty() + lists.others + lists.shared.orEmpty()
        if (rows.none { it.ref(key) == entry.ref(key) && it.savedAt != null }) return
        set(lists.copy(deleting = entry, error = null))
    }

    fun cancelDelete() {
        val lists = state ?: return
        set(lists.copy(deleting = null, focusRow = lists.deleting))
    }

    /**
     * Deletes the save asked about, by the state the question named (not whichever row has the focus now), then reads
     * the lists again; the row after it in its list takes the focus, else the one before. Until those lists are in,
     * nothing else starts: the deleted row is still listed.
     */
    fun confirmDelete() {
        val lists = state ?: return
        val entry = lists.deleting ?: return
        val id = generation
        val ref = entry.ref(key)
        val next = SaveLists.afterDelete(entry, key, lists.entries, lists.others, lists.shared)
        set(lists.copy(deleting = null, removing = true, error = null))
        scope.launch {
            val error = withContext(game) { api.deleteState(ref) }
            if (id != generation) return@launch
            if (error != null) {
                change { it.copy(removing = false, error = "Couldn't delete that save: $error", focusRow = entry) }
            } else {
                read(id, focusRow = next, removed = true)
            }
        }
    }

    /**
     * Shared [entry] with the game it holds read, if the lists hadn't read it yet, and put in its row; without a game
     * when its state says nothing of one. Null when the lists closed or opened again meanwhile.
     */
    suspend fun readHolds(entry: SlotEntry): SlotEntry? {
        if (entry.holds != null) return entry
        val id = generation
        val read = reader.preview(entry, key, path, discs.size > 1)
        if (id != generation) return null
        if (read == null) return entry
        change { it.withRow(read) }
        return read
    }

    /** Shows the owner's [error] in the lists' line. */
    fun fail(error: String) = change { it.copy(error = error) }

    fun clearError() = change { it.copy(error = null) }

    /**
     * Reads lists [id]: the game's own, its other discs' (in Load) and the shared ones, then each saved row's picture
     * (and a shared slot's game), the open tab's first. [focusRow] is to take the focus. A delete's re-read ([removed])
     * ends its busy state once the lists are in.
     */
    private fun read(
        id: Int,
        focusRow: SlotEntry? = null,
        removed: Boolean = false,
    ) {
        val lists = state ?: return
        val mode = lists.mode
        val key = key
        val path = path
        val discs = discs
        val lastSlot = lastSlot
        val hasKey = lists.hasKey
        val multiDisc = discs.size > 1
        listRead?.cancel()
        listRead =
            scope.launch {
                val entries = if (hasKey) reader.own(key, mode) else emptyList()
                val last = if (hasKey && lastSlot != null) withContext(game) { lastSlot() } else null
                // In Load, the states still filed under the game's other discs, as the migration left them or desktop
                // saved them.
                val others = if (hasKey && mode == SlotMode.Load) reader.others(key, discs) else emptyList()
                val shared = reader.shared(mode)
                if (id != generation) return@launch
                change {
                    it.copy(
                        entries = entries,
                        focus = if (lastSlot != null) SaveLists.focus(mode, entries, last) else null,
                        others = others,
                        shared = shared,
                        sharedFocus = if (lastSlot != null) SaveLists.focus(mode, shared, null) else null,
                        focusRow = focusRow,
                        removing = it.removing && !removed,
                    )
                }
                val own = entries.filter { it.savedAt != null } + others
                val sharedSaved = shared.filter { it.savedAt != null }
                val tab = state?.tab ?: SlotTab.ThisGame
                for (entry in if (tab == SlotTab.Shared) sharedSaved + own else own + sharedSaved) {
                    val read = reader.preview(entry, key, path, multiDisc) ?: continue
                    if (id != generation) return@launch
                    change { it.withRow(read) }
                }
            }
    }

    private fun change(update: (SaveListState) -> SaveListState) {
        state?.let { set(update(it)) }
    }

    private fun set(lists: SaveListState?) {
        state = lists
        onChange(lists)
    }
}
