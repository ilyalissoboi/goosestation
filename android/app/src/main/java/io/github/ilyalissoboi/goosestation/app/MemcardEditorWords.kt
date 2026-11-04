// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/** What the memory card editor says, from the design, shared by the controller, its screens and their tests. */
object MemcardEditorWords {
    fun blocks(count: Int) = if (count == 1) "1 block" else "$count blocks"

    fun blocksFree(free: Int) = "$free of 15 blocks free"

    const val NO_CARD_YET = "No card yet"
    const val UNREADABLE = "Can't read this card"

    fun changes(count: Int) = if (count == 1) "1 change not saved" else "$count changes not saved"

    fun deleted(title: String) = "Deleted · $title"

    fun copyTo(card: String) = "Copy to $card"

    fun needsBlocks(
        card: String,
        needed: Int,
        free: Int,
    ) = "$card needs $needed free ${if (needed == 1) "block" else "blocks"}; it has $free."

    fun replaceSameName(card: String) = "Replace the save of the same name on $card?"

    fun delete(title: String) = "Delete $title? You can undelete it while its blocks aren't reused."

    fun deletePermanently(title: String) = "Erase $title for good? It can't be undeleted."

    fun format(card: String) = "Erase every save on $card?"

    fun importCard(
        card: String,
        file: String,
    ) = "Replace everything on $card with $file?"

    const val SYNC_REMOVES = "Memory card sync is on: your other devices will see these saves removed."

    fun undeleteDuplicate(
        title: String,
        name: String,
    ) = "Can't undelete $title: this card already has a save named $name."

    fun undeleteReused(title: String) = "Can't undelete $title: another save has used its blocks."

    fun tooBig(file: String) = "$file is too big to be a memory card or a save."

    fun cantRead(
        file: String,
        reason: String,
    ) = "Couldn't read $file: $reason"

    fun cantImport(
        file: String,
        reason: String,
    ) = "Couldn't import $file: $reason"

    fun exported(title: String) = "Exported $title."

    fun cantExport(
        title: String,
        reason: String,
    ) = "Couldn't export $title: $reason"

    const val CANCEL = "Cancel"
}
