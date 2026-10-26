// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.net.Uri

/** What a picked data folder already holds: a settings.ini, and how many files its memcards folder has. */
data class DataFolderContents(
    val hasSettings: Boolean,
    val memoryCards: Int,
) {
    /** What GooseStation will do with the folder, as the setup screen says it. */
    fun describe(): String {
        if (!hasSettings && memoryCards == 0) return "This folder is empty. GooseStation will start fresh here."
        val cards = if (memoryCards == 1) "1 memory card" else "$memoryCards memory cards"
        val found =
            when {
                hasSettings && memoryCards > 0 -> "settings and $cards"
                hasSettings -> "settings"
                else -> cards
            }
        return "This folder already has GooseStation data ($found). GooseStation will use it."
    }

    companion object {
        /**
         * Lists the top of [treeUri], and its memcards folder if there is one. Null if the folder can't be read.
         * SAF queries: call it off the main thread.
         */
        fun read(
            lister: DocumentLister,
            treeUri: String,
        ): DataFolderContents? =
            runCatching {
                val top = lister.listChildren(treeUri, lister.rootDocumentId(treeUri)) ?: return null
                val memcards = top.firstOrNull { it.isDirectory && it.name == "memcards" }
                val cards = memcards?.let { lister.listChildren(treeUri, it.documentId) ?: return null }.orEmpty()
                DataFolderContents(
                    hasSettings = top.any { !it.isDirectory && it.name == "settings.ini" },
                    memoryCards = cards.count { !it.isDirectory },
                )
            }.getOrNull()
    }
}

/** A picked folder's name to show: its path within its storage, such as `GooseTest/games`. */
fun folderLabel(treeUri: String): String {
    val documentId = Uri.parse(treeUri).pathSegments.let { if (it.size >= 2 && it[0] == "tree") it[1] else null }
    documentId ?: return treeUri
    return documentId.substringAfter(':').ifEmpty { documentId }
}
