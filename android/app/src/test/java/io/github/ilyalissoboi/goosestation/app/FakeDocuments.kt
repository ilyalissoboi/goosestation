// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.DocumentRead
import io.github.ilyalissoboi.goosestation.storage.Documents
import io.github.ilyalissoboi.goosestation.storage.PickedDocument

/**
 * The file picker, as a test sets it: [picked] is what the next [open] gives (null: the player backs out), [reads]
 * what each URI reads as, [created] the URI the next [create] gives. What's written is kept in [written].
 */
class FakeDocuments : Documents {
    var picked: PickedDocument? = null
    val reads = mutableMapOf<String, DocumentRead>()
    var created: String? = null
    var suggested: String? = null
    var writeError: String? = null
    val written = mutableMapOf<String, ByteArray>()
    var lastLimit = 0

    override suspend fun open() = picked

    override suspend fun create(suggestedName: String): String? {
        suggested = suggestedName
        return created
    }

    override fun read(
        uri: String,
        limit: Int,
    ): DocumentRead {
        lastLimit = limit
        return reads[uri] ?: DocumentRead.Failed("not found")
    }

    override fun write(
        uri: String,
        bytes: ByteArray,
    ): String? {
        writeError?.let { return it }
        written[uri] = bytes
        return null
    }
}
