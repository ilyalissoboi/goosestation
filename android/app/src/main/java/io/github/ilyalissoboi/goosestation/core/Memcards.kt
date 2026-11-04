// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * A save on a memory card: the [firstBlock] it's found by, its [fileName] and [title], its size in [blocks], whether
 * it's [deleted] (it can be undeleted), and its icon's first frame, or null without one. Made by native code.
 */
class MemcardSave(
    val firstBlock: Int,
    val fileName: String,
    val title: String,
    val blocks: Int,
    val deleted: Boolean,
    val icon: ArgbImage?,
)

/** What's on a memory card: its [freeBlocks] (of 15) and its [saves], deleted ones too. Made by native code. */
class MemcardContents(
    val freeBlocks: Int,
    val saves: List<MemcardSave>,
) {
    /** For native code, which passes the saves as an array. */
    constructor(freeBlocks: Int, saves: Array<MemcardSave>) : this(freeBlocks, saves.toList())
}

/** An edit's new card, or null [bytes] with the core's reason, its [error]. Made by native code. */
class MemcardEdit(
    val bytes: ByteArray?,
    val error: String,
)

/**
 * What renaming a save would meet: [titleError] and [nameError] are the core's `MemoryCardHelpers::TitleError` and
 * `FileNameError` as numbers (0 for none, then in their order), [singleByteTitle] whether the stored title is in
 * single bytes, and the warnings. Made by native code.
 */
class MemcardRenameCheck(
    val titleError: Int,
    val nameError: Int,
    val singleByteTitle: Boolean,
    val titleNotShownExactly: Boolean,
    val nameNotStandard: Boolean,
) {
    companion object {
        const val TITLE_UNENCODABLE = 1
        const val TITLE_TOO_LONG = 2
        const val NAME_EMPTY = 1
        const val NAME_TOO_LONG = 2
        const val NAME_INVALID = 3
        const val NAME_DUPLICATE = 4
    }
}

/** How writing a card went: written, the file changed since it was read, sync is busy with it, or failed. */
enum class MemcardWriteOutcome {
    Saved,
    Changed,
    Busy,
    Failed,
}

/** A card write's [outcome], and the reason it [Failed][MemcardWriteOutcome.Failed]. Made by native code. */
class MemcardWrite(
    val outcome: MemcardWriteOutcome,
    val error: String,
)
