// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import java.net.URLDecoder

/** A folder picked to be the data folder, looked at: what it holds, or why it can't be the data folder. */
sealed interface DataFolderInspection {
    data class Usable(
        val contents: DataFolderContents,
    ) : DataFolderInspection

    /** Not usable, for the [reason] shown in the screen, which asks for another folder. */
    data class Refused(
        val reason: String,
    ) : DataFolderInspection
}

/**
 * What makes a folder unfit to be the data folder, checked when it's picked, in setup and when it changes:
 *
 * - It's a games folder, or holds one, or sits in one. The first pull would copy the games into the working copy.
 *   A BIOS folder there is fine: the app keeps its own copies of the images, which are small.
 * - Its storage can't create files, or can't rename them. The mirror replaces a file by writing a temporary one and
 *   renaming it into place; written in place instead, an interrupted write would leave a short file there that the
 *   next pull takes as the folder's change.
 */
object DataFolderCheck {
    const val DATA_OVERLAPS_GAMES =
        "The data folder can't be a games folder, nor hold one or sit in one. Pick another."
    const val GAMES_OVERLAP_DATA =
        "A games folder can't be the data folder, nor hold it or sit in it. Pick another."
    const val UNREADABLE = "Can't read this folder. Pick another."
    const val CANT_CREATE = "GooseStation can't create files in this folder. Pick another."
    const val CANT_REPLACE = "This folder's storage can't replace files safely. Pick another."

    /** The probe's name: a leftover from an interrupted [probe] is cleaned up as any `.goosetmp` is. */
    const val PROBE_NAME = ".goosestation-probe.goosetmp"

    private const val TAG = "DataFolderCheck"
    private const val TREE = "/tree/"

    /**
     * Whether the trees [a] and [b] are the same folder, or one is inside the other, as far as their document IDs
     * tell: on the same provider, one ID is the other's start, ending at a `/` or `:` (as in ExternalStorageProvider's
     * `primary:PS1/games`). A provider's opaque IDs (a cloud provider's, say) only match when equal.
     */
    fun overlaps(
        a: String,
        b: String,
    ): Boolean {
        val (authorityA, idA) = parse(a) ?: return a == b
        val (authorityB, idB) = parse(b) ?: return false
        if (authorityA != authorityB) return false
        return idA == idB || isInside(idA, idB) || isInside(idB, idA)
    }

    /** Whether [tree] [overlaps] any of [others]. */
    fun overlapsAny(
        tree: String,
        others: List<String>,
    ) = others.any { overlaps(tree, it) }

    /**
     * Looks at [treeUri] as a data folder, with [games] the games folders: refused if it [overlaps] one, can't be
     * read, or fails the [probe]; otherwise what it holds. SAF queries and a write: call it off the main thread.
     */
    fun inspect(
        resolver: ContentResolver,
        treeUri: String,
        games: List<String>,
    ): DataFolderInspection {
        if (overlapsAny(treeUri, games)) return DataFolderInspection.Refused(DATA_OVERLAPS_GAMES)
        val contents =
            DataFolderContents.read(SafDocumentLister(resolver), treeUri)
                ?: return DataFolderInspection.Refused(UNREADABLE)
        val refusal = probe(resolver, treeUri)
        return if (refusal != null) DataFolderInspection.Refused(refusal) else DataFolderInspection.Usable(contents)
    }

    /**
     * Whether the mirror can replace files in [treeUri]: its root must allow creating files, and a file created there
     * ([PROBE_NAME], deleted again) must allow renaming. Null if it can, otherwise why not, to show.
     */
    fun probe(
        resolver: ContentResolver,
        treeUri: String,
    ): String? {
        val tree = Uri.parse(treeUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val rootFlags = flags(resolver, root) ?: return UNREADABLE
        if (rootFlags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) return CANT_CREATE
        val probe =
            try {
                DocumentsContract.createDocument(resolver, root, "application/octet-stream", PROBE_NAME)
            } catch (e: Exception) {
                Log.w(TAG, "Can't create a file in $treeUri", e)
                null
            } ?: return CANT_CREATE
        try {
            val flags = flags(resolver, probe) ?: return UNREADABLE
            return if (flags and Document.FLAG_SUPPORTS_RENAME == 0) CANT_REPLACE else null
        } finally {
            try {
                DocumentsContract.deleteDocument(resolver, probe)
            } catch (e: Exception) {
                // The mirror's next run deletes it, as a leftover .goosetmp.
                Log.w(TAG, "Can't delete the probe in $treeUri", e)
            }
        }
    }

    private fun flags(
        resolver: ContentResolver,
        document: Uri,
    ): Int? =
        try {
            resolver.query(document, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't query $document", e)
            null
        }

    /** Whether the document [inner] is inside [outer], by their IDs. */
    private fun isInside(
        inner: String,
        outer: String,
    ): Boolean {
        if (outer.isEmpty() || inner.length <= outer.length || !inner.startsWith(outer)) return false
        return outer.last() == '/' || outer.last() == ':' || inner[outer.length] == '/'
    }

    /** A tree URI's authority and decoded document ID, without android.net.Uri, so it's testable on the JVM. */
    private fun parse(treeUri: String): Pair<String, String>? {
        val scheme = "content://"
        if (!treeUri.startsWith(scheme)) return null
        val authorityEnd = treeUri.indexOf('/', scheme.length)
        if (authorityEnd < 0 || !treeUri.startsWith(TREE, authorityEnd)) return null
        val encoded = treeUri.substring(authorityEnd + TREE.length).substringBefore('/')
        if (encoded.isEmpty()) return null
        // Uri.encode() leaves no '+' unescaped, so one here is itself, not a space.
        val documentId = runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull()
        return documentId?.let { treeUri.substring(scheme.length, authorityEnd) to it }
    }
}
