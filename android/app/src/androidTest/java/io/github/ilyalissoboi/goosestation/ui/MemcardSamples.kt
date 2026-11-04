// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import io.github.ilyalissoboi.goosestation.app.EditorCard
import io.github.ilyalissoboi.goosestation.app.EditorMessage
import io.github.ilyalissoboi.goosestation.app.EditorQuestion
import io.github.ilyalissoboi.goosestation.app.MemcardEditorActions
import io.github.ilyalissoboi.goosestation.app.MemcardEditorUi
import io.github.ilyalissoboi.goosestation.app.SavePanel
import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.MemcardContents
import io.github.ilyalissoboi.goosestation.core.MemcardFile
import io.github.ilyalissoboi.goosestation.core.MemcardKind
import io.github.ilyalissoboi.goosestation.core.MemcardSave

/** Made-up cards and saves for the memory card editor's screen tests. */
object MemcardSamples {
    private val BYTES = ByteArray(1)
    private val ICON = ArgbImage(16, 16, IntArray(256) { if (it % 3 == 0) 0xFFFF7A1A.toInt() else 0 })

    fun file(
        name: String,
        kind: MemcardKind = MemcardKind.Game,
        exists: Boolean = true,
    ) = MemcardFile("/data/memcards/$name.mcd", name, kind, exists)

    fun save(
        firstBlock: Int,
        fileName: String,
        title: String,
        blocks: Int = 1,
        deleted: Boolean = false,
        icon: Boolean = true,
    ) = MemcardSave(firstBlock, fileName, title, blocks, deleted, if (icon) ICON else null)

    fun card(
        file: MemcardFile,
        saves: List<MemcardSave>,
        knownToSync: Boolean = false,
    ) = EditorCard(
        file,
        BYTES,
        BYTES,
        MemcardContents(
            15 -
                saves
                    .filterNot {
                        it.deleted
                    }.sumOf { it.blocks },
            saves,
        ),
        knownToSync,
    )

    fun noCardYet(file: MemcardFile) = EditorCard(file, null, null, null, knownToSync = false)

    fun unreadable(file: MemcardFile) = EditorCard(file, BYTES, BYTES, null, knownToSync = false)

    val CRASH = file("Crash Bandicoot")
    val SHARED = file("Shared card 1", MemcardKind.Shared)
    val SPYRO = file("Spyro the Dragon", exists = false)
    val CRASH_SAVE = save(1, "BASCUS-94900", "Crash Bandicoot", blocks = 1)
    val DELETED_SAVE = save(2, "BASLUS-00067DRACULA", "Castlevania", blocks = 3, deleted = true)
    val CRASH_CARD = card(CRASH, listOf(CRASH_SAVE, DELETED_SAVE))
    val SHARED_CARD = card(SHARED, listOf(save(1, "BESLES-01234", "Tekken 3")))

    fun ui(
        left: EditorCard? = CRASH_CARD,
        right: EditorCard? = SHARED_CARD,
        cards: List<MemcardFile> = listOfNotNull(left?.file, right?.file, SPYRO).distinctBy { it.path },
        changes: Int = 0,
        message: EditorMessage? = null,
        question: EditorQuestion? = null,
        panel: SavePanel? = null,
        renameError: String? = null,
    ) = MemcardEditorUi(cards, listOf(left, right), changes, message, question, false, panel, renameError)
}

/** The editor's actions, recorded as words: "pick 0 /data/memcards/Spyro the Dragon.mcd", "openSave 0 1", "save". */
class RecordingEditorActions : MemcardEditorActions {
    val calls = mutableListOf<String>()

    override fun pick(
        pane: Int,
        path: String,
    ) {
        calls += "pick $pane $path"
    }

    override fun copy(
        pane: Int,
        save: MemcardSave,
    ) {
        calls += "copy $pane ${save.firstBlock}"
    }

    override fun delete(
        pane: Int,
        save: MemcardSave,
    ) {
        calls += "delete $pane ${save.firstBlock}"
    }

    override fun deletePermanently(
        pane: Int,
        save: MemcardSave,
    ) {
        calls += "deletePermanently $pane ${save.firstBlock}"
    }

    override fun undelete(
        pane: Int,
        save: MemcardSave,
    ) {
        calls += "undelete $pane ${save.firstBlock}"
    }

    override fun format(pane: Int) {
        calls += "format $pane"
    }

    override fun importSave(pane: Int) {
        calls += "importSave $pane"
    }

    override fun importCard(pane: Int) {
        calls += "importCard $pane"
    }

    override fun export(
        pane: Int,
        save: MemcardSave,
    ) {
        calls += "export $pane ${save.firstBlock}"
    }

    override fun answer(confirmed: Boolean) {
        calls += "answer $confirmed"
    }

    override fun choose(index: Int) {
        calls += "choose $index"
    }

    override fun clearMessage() {
        calls += "clearMessage"
    }

    override fun openSave(
        pane: Int,
        save: MemcardSave,
    ) {
        calls += "openSave $pane ${save.firstBlock}"
    }

    override fun startRename() {
        calls += "startRename"
    }

    override fun closePanel() {
        calls += "closePanel"
    }

    override fun rename(
        title: String,
        fileName: String,
    ) {
        calls += "rename $title|$fileName"
    }

    override fun save() {
        calls += "save"
    }

    override fun discard() {
        calls += "discard"
    }

    override fun back(leave: () -> Unit) {
        calls += "back"
    }
}
