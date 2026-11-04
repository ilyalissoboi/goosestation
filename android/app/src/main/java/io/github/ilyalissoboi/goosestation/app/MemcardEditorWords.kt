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

    /** The Memory cards page's Manage cards row: how many card files there are, or null while they're counted. */
    fun cardCount(count: Int?) =
        when (count) {
            null -> "Counting your cards…"
            0 -> "No cards yet"
            1 -> "1 card"
            else -> "$count cards"
        }

    // Renaming, in the desktop editor's meaning (memorycardeditorwindow.cpp), in the design system's voice.

    const val TITLE_UNENCODABLE = "The title has characters a memory card can't store."

    fun titleTooLong(singleByte: Boolean) =
        if (singleByte) {
            "The title is too long: it can be up to 64 characters, or 32 if any aren't ASCII."
        } else {
            "The title is too long: it can be up to 32 full-width characters."
        }

    const val NAME_EMPTY = "Enter a file name."
    const val NAME_TOO_LONG = "File names can be up to 20 characters."
    const val NAME_INVALID = "File names can use only ASCII letters, numbers, spaces and punctuation, but not * or ?."

    fun nameTaken(name: String) = "This card already has a save named \"$name\"."

    const val TITLE_NOT_SHOWN_EXACTLY =
        "Some characters in the current title, such as ー or ・, can't be shown exactly here. The new title is saved " +
            "as you see it, so those characters may look different in the game."

    fun nameNotStandard(name: String) =
        "\"$name\" doesn't follow the usual pattern for save names: B, a region letter, then the game's " +
            "product code, such as BASLUS-00067. The game may no longer find or load this save."

    fun renameSyncs(
        old: String,
        new: String,
    ) = "Memory card sync is on: your other devices will see the save \"$old\" deleted and a new save \"$new\" " +
        "added. The history of \"$old\" stays available under its old name."

    const val RENAME_ANYWAY = "Rename anyway?"

    // Saving and leaving.

    const val SAVED = "Saved"

    fun syncBusy(card: String) = "Couldn't save $card: memory card sync is busy with it. Try again in a moment."

    fun cantSave(
        card: String,
        reason: String,
    ) = "Couldn't save $card: $reason"

    /** The cards [names] changed under the editor: Save asks before writing over them. */
    fun changedOnDevice(names: List<String>) =
        if (names.size == 1) {
            "${names[0]} changed on this device since you opened it. Save over it?"
        } else {
            "${names.dropLast(1).joinToString(", ")} and ${names.last()} changed on this device since you opened " +
                "them. Save over them?"
        }

    fun saveOver(count: Int) = if (count == 1) "Save over it" else "Save over them"

    const val DISCARD_CHANGES = "Discard your changes?"
    const val SAVE_CHANGES = "Save your changes?"
    const val KEEP_EDITING = "Keep editing"
}
