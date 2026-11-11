// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.GameEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A game's Saves page, from its library tile: the [game], its open [tab], its own saves ([entries], the resume state
 * first; null while they're read), the states still under its other discs ([others]) and the [shared] slots, as the
 * in-game Load panel lists them. [deleting] is the save it asks to delete, [focusRow] the row to focus (after a
 * delete), [hardcore] whether hardcore mode would start (no save can start the game then), and [error] why the last
 * pick or delete didn't work.
 */
data class SavesPageState(
    val game: GameEntry,
    val tab: SlotTab,
    val entries: List<SlotEntry>? = null,
    val others: List<SlotEntry> = emptyList(),
    val shared: List<SlotEntry>? = null,
    val deleting: SlotEntry? = null,
    val removing: Boolean = false,
    val focusRow: SlotEntry? = null,
    val hardcore: Boolean = false,
    val error: String? = null,
) {
    /** The game has a key to keep save states under; a PSF has none. */
    val hasKey: Boolean get() = game.serial.isNotBlank()
}

/** What the Saves page's rows and keys do. */
interface SavesActions {
    /** L1 or R1: the other tab. */
    fun switchTab()

    /** A on a save: the game starts from it (a shared slot's, the game it holds). */
    fun pick(entry: SlotEntry)

    /** X or a long press on a save: asks before deleting it. */
    fun askDelete(entry: SlotEntry)

    fun confirmDelete()

    fun cancelDelete()
}

/**
 * The Saves page's state and actions. Main thread only; [reader] and [api] calls run on [game]. A pick starts a game
 * with [play] (which refuses in hardcore mode, saying why), among [games], the library's.
 */
class SavesPage(
    private val scope: CoroutineScope,
    private val game: CoroutineDispatcher,
    private val reader: SaveReader,
    private val api: GameCoreApi,
    private val games: () -> List<GameEntry>,
    private val play: (game: GameEntry, state: StateRef, refused: (String) -> Unit) -> Unit,
) : SavesActions {
    /** The open page, or null. */
    var state by mutableStateOf<SavesPageState?>(null)
        private set

    /** Changes as the page opens and closes, so a read for an earlier one is dropped. */
    private var pageId = 0

    /** The lists' read under way, and their pictures': one at a time, so a re-read never reads a picture twice. */
    private var listRead: Job? = null

    /** Opens [entry]'s Saves, on This game, or on Shared for a game without a key. */
    fun open(entry: GameEntry) {
        val id = ++pageId
        val hasKey = entry.serial.isNotBlank()
        state = SavesPageState(entry, if (hasKey) SlotTab.ThisGame else SlotTab.Shared)
        scope.launch {
            val hardcore = withContext(game) { api.hardcoreWouldStart() }
            if (id == pageId) change { it.copy(hardcore = hardcore) }
        }
        read(id)
    }

    fun close() {
        pageId++
        listRead?.cancel()
        state = null
    }

    override fun switchTab() {
        val page = state ?: return
        if (page.deleting != null || page.removing) return
        val tab = if (page.tab == SlotTab.ThisGame) SlotTab.Shared else SlotTab.ThisGame
        state = page.copy(tab = tab, error = null, focusRow = null)
    }

    override fun pick(entry: SlotEntry) {
        val page = state ?: return
        if (page.deleting != null || page.removing || entry.savedAt == null) return
        if (page.hardcore) return change { it.copy(error = GameStarts.HARDCORE_LOAD) }
        val key = page.game.serial
        if (!entry.shared) return play(page.game, entry.ref(key), ::refused)
        val id = pageId
        scope.launch {
            // A shared slot's game is read first if it hasn't been yet: it decides which game starts.
            val read = if (entry.holds != null) entry else reader.preview(entry, key, page.game.path, false)
            if (id != pageId) return@launch
            read?.let { row -> change { it.withRow(row) } }
            val holds = read?.holds
            val target = holds?.path?.let { path -> games().firstOrNull { it.path == path } }
            when {
                holds == null -> change { it.copy(error = UNREADABLE) }
                target == null -> change { it.copy(error = SaveLists.notInLibrary(read)) }
                else -> play(target, entry.ref(key), ::refused)
            }
        }
    }

    private fun refused(why: String) = change { it.copy(error = why) }

    override fun askDelete(entry: SlotEntry) {
        val page = state ?: return
        if (page.deleting != null || page.removing || entry.savedAt == null) return
        // Only a save the lists still have: one a re-read has dropped is gone already.
        val key = page.game.serial
        val rows = page.entries.orEmpty() + page.others + page.shared.orEmpty()
        if (rows.none { it.ref(key) == entry.ref(key) && it.savedAt != null }) return
        state = page.copy(deleting = entry, error = null)
    }

    override fun cancelDelete() {
        val page = state ?: return
        state = page.copy(deleting = null, focusRow = page.deleting)
    }

    /**
     * Deletes the save asked about, by the state the question named, then reads the lists again, focusing the next row.
     * Until those lists are in, nothing else starts: the deleted row is still listed.
     */
    override fun confirmDelete() {
        val page = state ?: return
        val entry = page.deleting ?: return
        val key = page.game.serial
        val ref = entry.ref(key)
        val id = pageId
        val next = SaveLists.afterDelete(entry, key, page.entries, page.others, page.shared)
        state = page.copy(deleting = null, removing = true)
        scope.launch {
            val error = withContext(game) { api.deleteState(ref) }
            if (id != pageId) return@launch
            if (error != null) {
                change { it.copy(removing = false, error = "Couldn't delete that save: $error", focusRow = entry) }
            } else {
                read(id, next)
            }
        }
    }

    /** Reads page [id]'s lists, then each row's picture and game; [focusRow] is to take the focus. */
    private fun read(
        id: Int,
        focusRow: SlotEntry? = null,
    ) {
        val entry = state?.game ?: return
        val key = entry.serial
        val multiDisc = entry.discs.size > 1
        listRead?.cancel()
        listRead =
            scope.launch {
                val own = if (key.isNotBlank()) reader.own(key, SlotMode.Load) else emptyList()
                val others = if (key.isNotBlank()) reader.others(key, entry.discs) else emptyList()
                val shared = reader.shared(SlotMode.Load)
                if (id != pageId) return@launch
                change {
                    it.copy(entries = own, others = others, shared = shared, focusRow = focusRow, removing = false)
                }
                val tab = state?.tab ?: SlotTab.ThisGame
                val rows = if (tab == SlotTab.Shared) shared + own + others else own + others + shared
                for (row in rows) {
                    val read = reader.preview(row, key, entry.path, multiDisc) ?: continue
                    if (id != pageId) return@launch
                    change { it.withRow(read) }
                }
            }
    }

    private fun change(update: (SavesPageState) -> SavesPageState) {
        state = state?.let(update)
    }

    private fun SavesPageState.withRow(read: SlotEntry): SavesPageState =
        copy(
            entries = entries?.let { SaveLists.withRow(it, read) },
            others = SaveLists.withRow(others, read),
            shared = shared?.let { SaveLists.withRow(it, read) },
        )

    private companion object {
        const val UNREADABLE = "Couldn't read that save."
    }
}
