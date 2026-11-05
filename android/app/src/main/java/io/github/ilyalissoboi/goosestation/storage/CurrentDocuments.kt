// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

/**
 * The file picker of the activity shown ([current]), which outlives it: with no activity, nothing is picked (as if the
 * player backed out), and reads and writes fail, saying why ([NOT_SHOWN]).
 */
class CurrentDocuments(
    private val current: () -> Documents?,
) : Documents {
    override suspend fun open(): PickedDocument? = current()?.open()

    override suspend fun create(suggestedName: String): String? = current()?.create(suggestedName)

    override fun read(
        uri: String,
        limit: Int,
    ): DocumentRead = current()?.read(uri, limit) ?: DocumentRead.Failed(NOT_SHOWN)

    // Null is a write that worked: not to be mistaken for no picker.
    override fun write(
        uri: String,
        bytes: ByteArray,
    ): String? {
        val documents = current() ?: return NOT_SHOWN
        return documents.write(uri, bytes)
    }

    companion object {
        const val NOT_SHOWN = "GooseStation isn't shown."
    }
}
