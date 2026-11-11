// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.GameEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A game's Saves page, from its library tile: the [game], and its [lists] as the in-game Load panel lists them (with
 * the delete asked or under way, the row to focus, and why the last pick or delete didn't work). [hardcore] says
 * whether hardcore mode would start: no save can start the game then.
 */
data class SavesPageState(
    val game: GameEntry,
    val lists: SaveListState,
    val hardcore: Boolean = false,
)

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
    reader: SaveReader,
    private val api: GameCoreApi,
    private val games: () -> List<GameEntry>,
    private val play: (game: GameEntry, state: StateRef, refused: (String) -> Unit) -> Unit,
) : SavesActions {
    /** The open page, or null. */
    var state by mutableStateOf<SavesPageState?>(null)
        private set

    /** Changes as the page opens and closes, so the hardcore check or a shared pick for an earlier one is dropped. */
    private var pageId = 0

    // The page's game, and whether hardcore mode would start, while it's open.
    private var opened: GameEntry? = null
    private var hardcore = false

    private val lists =
        SaveListModel(scope, game, reader, api) { lists ->
            val entry = opened
            state = if (entry != null && lists != null) SavesPageState(entry, lists, hardcore) else null
        }

    /** Opens [entry]'s Saves, on This game, or on Shared for a game without a key. */
    fun open(entry: GameEntry) {
        val id = ++pageId
        opened = entry
        hardcore = false
        lists.open(entry.serial, entry.path, entry.discs, SlotMode.Load)
        scope.launch {
            val wouldStart = withContext(game) { api.hardcoreWouldStart() }
            if (id != pageId) return@launch
            hardcore = wouldStart
            state = state?.copy(hardcore = wouldStart)
        }
    }

    fun close() {
        pageId++
        opened = null
        lists.close()
    }

    override fun switchTab() = lists.switchTab()

    override fun pick(entry: SlotEntry) {
        val page = state ?: return
        if (!page.lists.idle || entry.savedAt == null) return
        if (page.hardcore) return lists.fail(GameStarts.HARDCORE_LOAD)
        val key = page.game.serial
        if (!entry.shared) return play(page.game, entry.ref(key), lists::fail)
        val id = pageId
        scope.launch {
            // A shared slot's game is read first if it hasn't been yet: it decides which game starts. A page closed or
            // opened again since the pick, even before this ran, starts nothing.
            if (id != pageId) return@launch
            val read = lists.readHolds(entry) ?: return@launch
            if (id != pageId) return@launch
            val holds = read.holds
            val target = holds?.path?.let { path -> games().firstOrNull { it.path == path } }
            when {
                holds == null -> lists.fail(SaveLists.UNREADABLE)
                target == null -> lists.fail(SaveLists.notInLibrary(read))
                else -> play(target, entry.ref(key), lists::fail)
            }
        }
    }

    override fun askDelete(entry: SlotEntry) = lists.askDelete(entry)

    override fun cancelDelete() = lists.cancelDelete()

    override fun confirmDelete() = lists.confirmDelete()
}
