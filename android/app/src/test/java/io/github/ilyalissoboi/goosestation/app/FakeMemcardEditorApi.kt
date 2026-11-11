// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.MemcardContents
import io.github.ilyalissoboi.goosestation.core.MemcardEdit
import io.github.ilyalissoboi.goosestation.core.MemcardFile
import io.github.ilyalissoboi.goosestation.core.MemcardRenameCheck
import io.github.ilyalissoboi.goosestation.core.MemcardSave
import io.github.ilyalissoboi.goosestation.core.MemcardWrite
import io.github.ilyalissoboi.goosestation.core.MemcardWriteOutcome

/** A save on a [FakeCard]. */
data class FakeSave(
    val fileName: String,
    val title: String = fileName,
    val blocks: Int = 1,
    val deleted: Boolean = false,
    val data: Int = 0,
)

/**
 * A card as [FakeMemcardEditorApi] keeps it: its saves, the first at block 1 and each after the one before. Its bytes
 * are text (the editor never looks inside them); [JUNK] is a file that isn't a card.
 */
data class FakeCard(
    val saves: List<FakeSave> = emptyList(),
) {
    val freeBlocks get() = 15 - saves.filterNot { it.deleted }.sumOf { it.blocks }

    fun bytes(): ByteArray =
        (HEADER + saves.joinToString("") { "${it.fileName}|${it.title}|${it.blocks}|${it.deleted}|${it.data}\n" })
            .toByteArray()

    /** The save at [firstBlock]. */
    fun at(firstBlock: Int): FakeSave? = blocksOf().entries.firstOrNull { it.value == firstBlock }?.key

    fun firstBlockOf(save: FakeSave): Int = blocksOf().getValue(save)

    private fun blocksOf(): Map<FakeSave, Int> {
        var next = 1
        return saves.associateWith { save -> next.also { next += save.blocks } }
    }

    companion object {
        private const val HEADER = "CARD\n"
        val JUNK = "junk".toByteArray()

        fun of(bytes: ByteArray?): FakeCard? {
            val text = bytes?.toString(Charsets.UTF_8) ?: return null
            if (!text.startsWith(HEADER)) return null
            return FakeCard(
                text.removePrefix(HEADER).lines().filter { it.isNotEmpty() }.map {
                    val (name, title, blocks, deleted, data) = it.split('|')
                    FakeSave(name, title, blocks.toInt(), deleted.toBoolean(), data.toInt())
                },
            )
        }
    }
}

/**
 * The editor's core calls, on [FakeCard]s: [files] are the card files by path, [cards] what [listCards] gives. A save
 * file to import is a [FakeSave]'s line ([saveFile]). Each call is recorded in [calls].
 */
class FakeMemcardEditorApi : MemcardEditorApi {
    val calls = mutableListOf<String>()
    var cards: List<MemcardFile> = emptyList()
    val files = mutableMapOf<String, ByteArray>()
    val known = mutableSetOf<String>()
    val writeOutcomes = mutableMapOf<String, MemcardWriteOutcome>()
    var undeleteRefused = false
    var importError: String? = null
    var titleNotShownExactly = false
    var singleByteTitle = false

    override fun listCards(games: List<MemcardGame>): List<MemcardFile> {
        calls += "listCards"
        return cards.map { MemcardFile(it.path, it.name, it.kind, files.containsKey(it.path)) }
    }

    override fun readCard(path: String) = files[path]?.copyOf()

    override fun contents(card: ByteArray): MemcardContents? {
        val fake = FakeCard.of(card) ?: return null
        return MemcardContents(
            fake.freeBlocks,
            fake.saves.map { MemcardSave(fake.firstBlockOf(it), it.fileName, it.title, it.blocks, it.deleted, null) },
        )
    }

    override fun format() = FakeCard().bytes()

    override fun copySave(
        from: ByteArray,
        firstBlock: Int,
        to: ByteArray,
        replace: Boolean,
    ): MemcardEdit {
        calls += "copySave $firstBlock replace=$replace"
        val save = FakeCard.of(from)!!.at(firstBlock)!!
        val target = FakeCard.of(to)!!
        val live = target.saves.filter { it.fileName == save.fileName && !it.deleted }
        if (live.isNotEmpty() && !replace) return MemcardEdit(null, "Already there.")
        val kept = target.saves - live.toSet()
        val result = FakeCard(kept + save)
        if (result.freeBlocks < 0) return MemcardEdit(null, "Insufficient free blocks.")
        return MemcardEdit(result.bytes(), "")
    }

    override fun deleteSave(
        card: ByteArray,
        firstBlock: Int,
        permanently: Boolean,
    ): ByteArray? {
        calls += "deleteSave $firstBlock permanently=$permanently"
        val fake = FakeCard.of(card)!!
        val save = fake.at(firstBlock) ?: return null
        val saves =
            if (permanently) {
                fake.saves - save
            } else {
                fake.saves.map {
                    if (it ==
                        save
                    ) {
                        it.copy(deleted = true)
                    } else {
                        it
                    }
                }
            }
        return FakeCard(saves).bytes()
    }

    override fun undeleteSave(
        card: ByteArray,
        firstBlock: Int,
    ): ByteArray? {
        calls += "undeleteSave $firstBlock"
        if (undeleteRefused) return null
        val fake = FakeCard.of(card)!!
        val save = fake.at(firstBlock) ?: return null
        return FakeCard(fake.saves.map { if (it == save) it.copy(deleted = false) else it }).bytes()
    }

    override fun checkRename(
        card: ByteArray,
        firstBlock: Int,
        title: String?,
        fileName: String?,
    ): MemcardRenameCheck {
        calls += "checkRename $title $fileName"
        val fake = FakeCard.of(card)!!
        val others = fake.saves.filterNot { it.deleted || it == fake.at(firstBlock) }.map { it.fileName }
        val nameError =
            when {
                fileName == null -> 0
                fileName.isEmpty() -> MemcardRenameCheck.NAME_EMPTY
                fileName.length > 20 -> MemcardRenameCheck.NAME_TOO_LONG
                '*' in fileName || '?' in fileName -> MemcardRenameCheck.NAME_INVALID
                fileName in others -> MemcardRenameCheck.NAME_DUPLICATE
                else -> 0
            }
        val titleError =
            when {
                title == null -> 0
                '☃' in title -> MemcardRenameCheck.TITLE_UNENCODABLE
                title.length > 32 -> MemcardRenameCheck.TITLE_TOO_LONG
                else -> 0
            }
        return MemcardRenameCheck(
            titleError,
            nameError,
            singleByteTitle,
            title != null && titleNotShownExactly,
            fileName != null && !fileName.startsWith("B"),
        )
    }

    override fun rename(
        card: ByteArray,
        firstBlock: Int,
        title: String?,
        fileName: String?,
    ): ByteArray? {
        calls += "rename $title $fileName"
        val fake = FakeCard.of(card)!!
        val save = fake.at(firstBlock) ?: return null
        val renamed = save.copy(fileName = fileName ?: save.fileName, title = title ?: save.title)
        return FakeCard(fake.saves.map { if (it == save) renamed else it }).bytes()
    }

    override fun importSave(
        card: ByteArray,
        fileName: String,
        data: ByteArray,
    ): MemcardEdit {
        calls += "importSave $fileName"
        importError?.let { return MemcardEdit(null, it) }
        val fake = FakeCard.of(card)!!
        val save = FakeCard.of(data)?.saves?.single() ?: return MemcardEdit(null, "Unknown save format.")
        return MemcardEdit(FakeCard(fake.saves + save).bytes(), "")
    }

    override fun importCard(
        fileName: String,
        data: ByteArray,
    ): MemcardEdit {
        calls += "importCard $fileName"
        importError?.let { return MemcardEdit(null, it) }
        return FakeCard.of(data)?.let { MemcardEdit(it.bytes(), "") } ?: MemcardEdit(null, "Unknown extension.")
    }

    override fun exportSave(
        card: ByteArray,
        firstBlock: Int,
    ): MemcardEdit {
        calls += "exportSave $firstBlock"
        val save = FakeCard.of(card)!!.at(firstBlock)!!
        return MemcardEdit(saveFile(save), "")
    }

    override fun isKnownToSync(path: String) = path in known

    override fun writeCard(
        path: String,
        card: ByteArray,
        expected: ByteArray?,
        force: Boolean,
    ): MemcardWrite {
        calls += "writeCard $path force=$force"
        writeOutcomes[path]?.let { return MemcardWrite(it, if (it == MemcardWriteOutcome.Failed) "Disk full." else "") }
        val current = files[path]
        if (!force && !sameBytes(current, expected)) return MemcardWrite(MemcardWriteOutcome.Changed, "")
        files[path] = card.copyOf()
        return MemcardWrite(MemcardWriteOutcome.Saved, "")
    }

    companion object {
        /** A save file holding [save], as [importSave] reads one. */
        fun saveFile(save: FakeSave) = FakeCard(listOf(save)).bytes()
    }
}
