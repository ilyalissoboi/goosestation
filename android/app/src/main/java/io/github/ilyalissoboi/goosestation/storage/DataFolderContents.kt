// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.net.Uri
import io.github.ilyalissoboi.goosestation.mirror.DataLayout

/**
 * What a picked data folder already holds: a settings.ini, how many files its memcards folder has, and the names at
 * its top that aren't GooseStation's ([others]: a games folder, say), which the mirror leaves alone.
 */
data class DataFolderContents(
    val hasSettings: Boolean,
    val memoryCards: Int,
    val others: List<String> = emptyList(),
) {
    private val noData get() = !hasSettings && memoryCards == 0

    /** What GooseStation will do with the folder, as the setup screen says it. */
    fun describe() =
        summary() +
            when {
                !noData -> " GooseStation will use it."
                others.isEmpty() -> " GooseStation will start fresh here."
                else -> " GooseStation will start fresh in this folder."
            }

    /** What the folder holds, and no more: when changing the data folder, the user chooses what's done with it. */
    fun summary(): String {
        if (noData) return if (others.isEmpty()) "This folder is empty." else "No GooseStation data here yet."
        val cards = if (memoryCards == 1) "1 memory card" else "$memoryCards memory cards"
        val found =
            when {
                hasSettings && memoryCards > 0 -> "settings and $cards"
                hasSettings -> "settings"
                else -> cards
            }
        return "This folder already has GooseStation data ($found)."
    }

    /** The line shown under [describe] or [summary] when the folder holds [others]; null when it holds none. */
    fun otherThings(): String? {
        if (others.isEmpty()) return null
        val shown = others.take(SHOWN).joinToString(", ")
        val more = if (others.size > SHOWN) " and ${others.size - SHOWN} more" else ""
        return "This folder also holds other things ($shown$more). GooseStation leaves them alone."
    }

    companion object {
        private const val SHOWN = 3

        /**
         * Lists the top of [treeUri], and its memcards folder if there is one. Null if the folder can't be read.
         * The [others] are those of the [DataLayout.DEFAULT], as the core's settings aren't read yet. SAF queries:
         * call it off the main thread.
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
                    others = DataLayout.DEFAULT.others(top.map { it.name to it.isDirectory }),
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
