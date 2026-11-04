// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.MemcardContents
import io.github.ilyalissoboi.goosestation.core.MemcardFile
import io.github.ilyalissoboi.goosestation.core.MemcardSave
import io.github.ilyalissoboi.goosestation.storage.DocumentRead
import io.github.ilyalissoboi.goosestation.storage.Documents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether a card in the editor has no file yet, isn't a card, or is ready to edit. */
enum class CardState {
    NoCardYet,
    Unreadable,
    Ready,
}

/**
 * A card in the editor: its [file], the bytes [onFile] (null: no file) as last read or written, its [current] bytes
 * with the edits (null: no card yet), what's on it ([contents], null unless it's a card), and whether sync knows it.
 */
class EditorCard(
    val file: MemcardFile,
    val onFile: ByteArray?,
    val current: ByteArray?,
    val contents: MemcardContents?,
    val knownToSync: Boolean,
) {
    val state: CardState
        get() =
            when {
                current == null -> CardState.NoCardYet
                contents == null -> CardState.Unreadable
                else -> CardState.Ready
            }

    /** Whether it has edits: its bytes aren't the file's. */
    val edited: Boolean get() = !sameBytes(current, onFile)

    internal fun with(
        current: ByteArray?,
        contents: MemcardContents?,
    ) = EditorCard(file, onFile, current, contents, knownToSync)
}

/** A line the editor shows under its title: what happened, or, as an [error], what went wrong. */
data class EditorMessage(
    val text: String,
    val error: Boolean,
)

/** One more answer to an [EditorQuestion], besides its confirm and its safe answer. */
class QuestionChoice(
    val label: String,
    val danger: Boolean,
    val action: () -> Unit,
)

/**
 * A question the editor asks before it acts: its [text], the button that does it ([confirm], which destroys or
 * replaces something), any [choices] besides, and the [safe] answer, which does nothing.
 */
class EditorQuestion(
    val text: String,
    val confirm: String,
    val onConfirm: () -> Unit,
    val choices: List<QuestionChoice> = emptyList(),
    val safe: String = MemcardEditorWords.CANCEL,
)

/** What the editor's screen shows: the [cards] to pick from, the card in each of the two [panes], and the rest. */
data class MemcardEditorUi(
    val cards: List<MemcardFile>,
    val panes: List<EditorCard?>,
    val changes: Int,
    val message: EditorMessage?,
    val question: EditorQuestion?,
    val working: Boolean,
)

/** What the editor's screen can ask of it. */
interface MemcardEditorActions {
    fun pick(
        pane: Int,
        path: String,
    )

    fun copy(
        pane: Int,
        save: MemcardSave,
    )

    fun delete(
        pane: Int,
        save: MemcardSave,
    )

    fun deletePermanently(
        pane: Int,
        save: MemcardSave,
    )

    fun undelete(
        pane: Int,
        save: MemcardSave,
    )

    fun format(pane: Int)

    fun importSave(pane: Int)

    fun importCard(pane: Int)

    fun export(
        pane: Int,
        save: MemcardSave,
    )

    /** Answers the question: [confirmed] does what it asked about; otherwise nothing is done. */
    fun answer(confirmed: Boolean)

    /** Takes the question's extra choice [index]. */
    fun choose(index: Int)

    fun clearMessage()
}

/**
 * The memory card editor: the cards there are, two of them shown side by side, and edits held in memory, per card,
 * until they're saved. Its core calls go through [api] on [core], and the file picker's reads and writes through
 * [documents] on [io]; [syncOn] says whether memory card sync is on. Main thread only. One action runs at a time:
 * others are ignored meanwhile ([MemcardEditorUi.working]).
 */
class MemcardEditor(
    private val scope: CoroutineScope,
    private val core: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val api: MemcardEditorApi,
    private val documents: Documents,
    private val syncOn: () -> Boolean,
) : MemcardEditorActions {
    private var files by mutableStateOf<List<MemcardFile>?>(null)
    private val loaded = mutableStateMapOf<String, EditorCard>()
    private var shown by mutableStateOf(listOf<String?>(null, null))
    private var changes by mutableIntStateOf(0)
    private var message by mutableStateOf<EditorMessage?>(null)
    private var question by mutableStateOf<EditorQuestion?>(null)
    private var working by mutableStateOf(false)

    /** What the screen shows, or null until the cards are listed. */
    val ui: MemcardEditorUi?
        get() =
            files?.let { cards ->
                MemcardEditorUi(cards, shown.map { it?.let(loaded::get) }, changes, message, question, working)
            }

    /** Lists the cards for [games], forgetting any edits, and shows the first two whose files exist. */
    fun open(games: List<GameEntry>) {
        files = null
        loaded.clear()
        changes = 0
        message = null
        question = null
        act {
            val cards = withContext(core) { api.listCards(MemcardGames.of(games)) }
            val first = (cards.filter { it.exists } + cards.filterNot { it.exists }).take(2)
            for (card in first) loaded[card.path] = load(card)
            shown = first.map { it.path } + List(2 - first.size) { null }
            files = cards
        }
    }

    override fun pick(
        pane: Int,
        path: String,
    ) {
        val other = 1 - pane
        if (shown[other] == path) {
            shown = shown.reversed()
            return
        }
        val file = files?.firstOrNull { it.path == path } ?: return
        act {
            if (path !in loaded) loaded[path] = load(file)
            shown = shown.toMutableList().also { it[pane] = path }
        }
    }

    override fun copy(
        pane: Int,
        save: MemcardSave,
    ) {
        val from = card(pane) ?: return
        val to = card(1 - pane) ?: return
        if (to.state == CardState.Unreadable || save.deleted) return
        val replaced = to.contents?.saves?.firstOrNull { !it.deleted && it.fileName == save.fileName }
        val free = (to.contents?.freeBlocks ?: FULL_CARD) + (replaced?.blocks ?: 0)
        val doCopy = {
            act {
                if (free < save.blocks) {
                    message =
                        EditorMessage(MemcardEditorWords.needsBlocks(to.file.name, save.blocks, free), error = true)
                    return@act
                }
                val base = to.current ?: withContext(core) { api.format() }
                val edit = withContext(core) { api.copySave(from.current!!, save.firstBlock, base, replaced != null) }
                val bytes = edit.bytes
                if (bytes == null) message = EditorMessage(edit.error, error = true) else apply(to.file.path, bytes)
            }
        }
        if (replaced != null && free >= save.blocks) {
            ask(EditorQuestion(MemcardEditorWords.replaceSameName(to.file.name), REPLACE, doCopy))
        } else {
            doCopy()
        }
    }

    override fun delete(
        pane: Int,
        save: MemcardSave,
    ) = removing(pane, MemcardEditorWords.delete(save.title), DELETE) { card ->
        api.deleteSave(card, save.firstBlock, permanently = false)
    }

    override fun deletePermanently(
        pane: Int,
        save: MemcardSave,
    ) = removing(pane, MemcardEditorWords.deletePermanently(save.title), ERASE) { card ->
        api.deleteSave(card, save.firstBlock, permanently = true)
    }

    override fun format(pane: Int) {
        val card = card(pane) ?: return
        removing(pane, MemcardEditorWords.format(card.file.name), ERASE) { api.format() }
    }

    override fun undelete(
        pane: Int,
        save: MemcardSave,
    ) {
        val card = card(pane) ?: return
        val current = card.current ?: return
        act {
            if (card.contents?.saves?.any { !it.deleted && it.fileName == save.fileName } == true) {
                message = EditorMessage(MemcardEditorWords.undeleteDuplicate(save.title, save.fileName), error = true)
                return@act
            }
            val undeleted = withContext(core) { api.undeleteSave(current, save.firstBlock) }
            if (undeleted == null) {
                message = EditorMessage(MemcardEditorWords.undeleteReused(save.title), error = true)
            } else {
                apply(card.file.path, undeleted)
            }
        }
    }

    override fun importSave(pane: Int) {
        val card = card(pane) ?: return
        if (card.state == CardState.Unreadable) return
        act {
            val (picked, data) = pickAndRead() ?: return@act
            val base = card.current ?: withContext(core) { api.format() }
            val edit = withContext(core) { api.importSave(base, picked, data) }
            val bytes = edit.bytes
            if (bytes == null) {
                message = EditorMessage(MemcardEditorWords.cantImport(picked, edit.error), error = true)
            } else {
                apply(card.file.path, bytes)
            }
        }
    }

    override fun importCard(pane: Int) {
        val card = card(pane) ?: return
        act {
            val (picked, data) = pickAndRead() ?: return@act
            val edit = withContext(core) { api.importCard(picked, data) }
            val bytes = edit.bytes
            if (bytes == null) {
                message = EditorMessage(MemcardEditorWords.cantImport(picked, edit.error), error = true)
                return@act
            }
            ask(
                EditorQuestion(
                    withSyncLine(card, MemcardEditorWords.importCard(card.file.name, picked)),
                    REPLACE,
                    { act { apply(card.file.path, bytes) } },
                ),
            )
        }
    }

    override fun export(
        pane: Int,
        save: MemcardSave,
    ) {
        val card = card(pane) ?: return
        val current = card.current ?: return
        act {
            val uri = documents.create("${save.fileName}.mcs") ?: return@act
            val edit = withContext(core) { api.exportSave(current, save.firstBlock) }
            val bytes = edit.bytes
            val failure = if (bytes == null) edit.error else withContext(io) { documents.write(uri, bytes) }
            message =
                if (failure == null) {
                    EditorMessage(MemcardEditorWords.exported(save.title), error = false)
                } else {
                    EditorMessage(MemcardEditorWords.cantExport(save.title, failure), error = true)
                }
        }
    }

    override fun answer(confirmed: Boolean) {
        val asked = question ?: return
        question = null
        if (confirmed) asked.onConfirm()
    }

    override fun choose(index: Int) {
        val asked = question ?: return
        val choice = asked.choices.getOrNull(index) ?: return
        question = null
        choice.action()
    }

    override fun clearMessage() {
        message = null
    }

    private fun card(pane: Int): EditorCard? = shown.getOrNull(pane)?.let(loaded::get)

    // Asks before removing saves from the pane's card: [edit] makes the new card from its bytes.
    private fun removing(
        pane: Int,
        text: String,
        confirm: String,
        edit: (ByteArray) -> ByteArray?,
    ) {
        val card = card(pane) ?: return
        val current = card.current ?: return
        if (working) return
        message = null
        ask(
            EditorQuestion(withSyncLine(card, text), confirm, {
                act { withContext(core) { edit(current) }?.let { apply(card.file.path, it) } }
            }),
        )
    }

    private fun withSyncLine(
        card: EditorCard,
        text: String,
    ) = if (card.knownToSync && syncOn()) "$text\n\n${MemcardEditorWords.SYNC_REMOVES}" else text

    private fun ask(asked: EditorQuestion) {
        question = asked
    }

    // The picked document's name and bytes, or null (having said why, unless the player backed out).
    private suspend fun pickAndRead(): Pair<String, ByteArray>? {
        val picked = documents.open() ?: return null
        return when (val read = withContext(io) { documents.read(picked.uri, READ_LIMIT) }) {
            is DocumentRead.Bytes -> picked.displayName to read.bytes
            DocumentRead.TooBig -> {
                message = EditorMessage(MemcardEditorWords.tooBig(picked.displayName), error = true)
                null
            }
            is DocumentRead.Failed -> {
                message = EditorMessage(MemcardEditorWords.cantRead(picked.displayName, read.reason), error = true)
                null
            }
        }
    }

    private suspend fun load(file: MemcardFile): EditorCard =
        withContext(core) {
            val bytes = api.readCard(file.path)
            EditorCard(file, bytes, bytes, bytes?.let(api::contents), api.isKnownToSync(file.path))
        }

    // One edit to the card at [path]: its new bytes.
    private suspend fun apply(
        path: String,
        bytes: ByteArray,
    ) {
        val card = loaded[path] ?: return
        val contents = withContext(core) { api.contents(bytes) }
        loaded[path] = card.with(bytes, contents)
        changes = if (loaded.values.any { it.edited }) changes + 1 else 0
    }

    // Acts: runs [block] as the one action under way, unless one already is; the message goes first.
    private fun act(block: suspend () -> Unit) {
        if (working) return
        working = true
        message = null
        scope.launch {
            try {
                block()
            } finally {
                working = false
            }
        }
    }

    private companion object {
        const val FULL_CARD = 15
        const val READ_LIMIT = 256 * 1024
        const val REPLACE = "Replace"
        const val DELETE = "Delete"
        const val ERASE = "Erase"
    }
}

/** Whether two byte arrays, either of which may be missing, are the same. */
internal fun sameBytes(
    a: ByteArray?,
    b: ByteArray?,
) = if (a == null || b == null) a == b else a.contentEquals(b)
