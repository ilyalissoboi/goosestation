// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.MemcardContents
import io.github.ilyalissoboi.goosestation.core.MemcardEdit
import io.github.ilyalissoboi.goosestation.core.MemcardFile
import io.github.ilyalissoboi.goosestation.core.MemcardRenameCheck
import io.github.ilyalissoboi.goosestation.core.MemcardWrite
import io.github.ilyalissoboi.goosestation.core.NativeLibrary

/**
 * The memory card editor's calls into the core, which tests replace. Each takes a card as its bytes and gives the new
 * ones, and a save is found by its first block. They wait for the core: call them on the core's dispatcher.
 */
interface MemcardEditorApi {
    /** The cards to list for [games]: [NativeLibrary.getMemcardFiles]. */
    fun listCards(games: List<MemcardGame>): List<MemcardFile>

    /** The card file's bytes, at most 256 KiB, or null without a file. */
    fun readCard(path: String): ByteArray?

    /** What's on [card], or null unless it's a memory card image (128 KiB, starting "MC"). */
    fun contents(card: ByteArray): MemcardContents?

    fun format(): ByteArray

    /** Copies [from]'s live save at [firstBlock] to [to]; [replace] first clears a live save of the same name there. */
    fun copySave(
        from: ByteArray,
        firstBlock: Int,
        to: ByteArray,
        replace: Boolean,
    ): MemcardEdit

    /** Deletes the save, [permanently] or so it can be undeleted; null without such a save. */
    fun deleteSave(
        card: ByteArray,
        firstBlock: Int,
        permanently: Boolean,
    ): ByteArray?

    /** Undeletes the save, or null when it can't be: another save has its blocks. */
    fun undeleteSave(
        card: ByteArray,
        firstBlock: Int,
    ): ByteArray?

    /** What a rename to [title] and [fileName] (null: unchanged) would meet, by the core's rules. */
    fun checkRename(
        card: ByteArray,
        firstBlock: Int,
        title: String?,
        fileName: String?,
    ): MemcardRenameCheck

    /** The renamed card, or null if [checkRename] finds an error. */
    fun rename(
        card: ByteArray,
        firstBlock: Int,
        title: String?,
        fileName: String?,
    ): ByteArray?

    /** Imports [data], a file named [fileName] (its extension picks the format), as a save on [card]. */
    fun importSave(
        card: ByteArray,
        fileName: String,
        data: ByteArray,
    ): MemcardEdit

    /** [data], a card file named [fileName] (its extension picks the format), as a card. */
    fun importCard(
        fileName: String,
        data: ByteArray,
    ): MemcardEdit

    /** The live save as an .mcs file. */
    fun exportSave(
        card: ByteArray,
        firstBlock: Int,
    ): MemcardEdit

    /** Whether memory card sync knows the card at [path]: a game used it here, or it came from another device. */
    fun isKnownToSync(path: String): Boolean

    /**
     * Writes [card] to [path], unless (without [force]) the file isn't [expected] any more (null: no file). A card sync
     * knows is written under its hold, so it syncs as one change.
     */
    fun writeCard(
        path: String,
        card: ByteArray,
        expected: ByteArray?,
        force: Boolean,
    ): MemcardWrite
}

object NativeMemcardEditorApi : MemcardEditorApi {
    override fun listCards(games: List<MemcardGame>) =
        NativeLibrary
            .getMemcardFiles(
                games.map { it.title }.toTypedArray(),
                games.map { it.serial }.toTypedArray(),
                games.map { it.bootPath }.toTypedArray(),
                games.map { it.playlist }.toBooleanArray(),
            ).toList()

    override fun readCard(path: String) = NativeLibrary.readMemcard(path)

    override fun contents(card: ByteArray) = NativeLibrary.getMemcardContents(card)

    override fun format() = NativeLibrary.formatMemcard()

    override fun copySave(
        from: ByteArray,
        firstBlock: Int,
        to: ByteArray,
        replace: Boolean,
    ) = NativeLibrary.copyMemcardSave(from, firstBlock, to, replace)

    override fun deleteSave(
        card: ByteArray,
        firstBlock: Int,
        permanently: Boolean,
    ) = NativeLibrary.deleteMemcardSave(card, firstBlock, permanently)

    override fun undeleteSave(
        card: ByteArray,
        firstBlock: Int,
    ) = NativeLibrary.undeleteMemcardSave(card, firstBlock)

    override fun checkRename(
        card: ByteArray,
        firstBlock: Int,
        title: String?,
        fileName: String?,
    ) = NativeLibrary.checkMemcardRename(card, firstBlock, title, fileName)

    override fun rename(
        card: ByteArray,
        firstBlock: Int,
        title: String?,
        fileName: String?,
    ) = NativeLibrary.renameMemcardSave(card, firstBlock, title, fileName)

    override fun importSave(
        card: ByteArray,
        fileName: String,
        data: ByteArray,
    ) = NativeLibrary.importMemcardSave(card, fileName, data)

    override fun importCard(
        fileName: String,
        data: ByteArray,
    ) = NativeLibrary.importMemcard(fileName, data)

    override fun exportSave(
        card: ByteArray,
        firstBlock: Int,
    ) = NativeLibrary.exportMemcardSave(card, firstBlock)

    override fun isKnownToSync(path: String) = NativeLibrary.isMemcardKnownToSync(path)

    override fun writeCard(
        path: String,
        card: ByteArray,
        expected: ByteArray?,
        force: Boolean,
    ) = NativeLibrary.writeMemcard(path, card, expected, force)
}
