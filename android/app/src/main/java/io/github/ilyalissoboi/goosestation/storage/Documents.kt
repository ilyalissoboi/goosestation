// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

/** A document the player picked: its `content://` [uri], and the [displayName] it's shown by (its file name). */
data class PickedDocument(
    val uri: String,
    val displayName: String,
)

/** What reading a document gave: its bytes, too many of them, or a failure with its reason. */
sealed interface DocumentRead {
    class Bytes(
        val bytes: ByteArray,
    ) : DocumentRead

    data object TooBig : DocumentRead

    class Failed(
        val reason: String,
    ) : DocumentRead
}

/**
 * Files in and out through Android's file picker, by `content://` documents, never paths. [open] and [create] are
 * main thread only; [read] and [write] are binder calls, for a background thread.
 */
interface Documents {
    /** Opens the picker for a file to read: the one picked, or null when the player backs out. */
    suspend fun open(): PickedDocument?

    /** Opens the picker for a new file, named [suggestedName] to start with: its URI, or null when backed out of. */
    suspend fun create(suggestedName: String): String?

    /** Reads the document at [uri], refusing one of more than [limit] bytes. */
    fun read(
        uri: String,
        limit: Int,
    ): DocumentRead

    /** Writes [bytes] as the whole document at [uri]: null once written, else why not. */
    fun write(
        uri: String,
        bytes: ByteArray,
    ): String?
}
