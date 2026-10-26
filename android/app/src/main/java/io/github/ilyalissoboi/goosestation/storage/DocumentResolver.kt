// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.util.Locale

/** Lists the documents of a tree, one folder at a time. */
interface DocumentLister {
    /** The children of [parentDocumentId], or null if it can't be listed. */
    fun listChildren(
        treeUri: String,
        parentDocumentId: String,
    ): List<NativeDocument>?

    fun rootDocumentId(treeUri: String): String
}

/** Lists through the storage access framework. A provider's exceptions (no grant, for one) are passed on. */
class SafDocumentLister(
    private val resolver: ContentResolver,
) : DocumentLister {
    override fun rootDocumentId(treeUri: String): String = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))

    override fun listChildren(
        treeUri: String,
        parentDocumentId: String,
    ): List<NativeDocument>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(treeUri), parentDocumentId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: return null
        return cursor.use {
            val children = ArrayList<NativeDocument>(it.count)
            while (it.moveToNext()) {
                val documentId = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                children +=
                    NativeDocument(
                        name = name,
                        documentId = documentId,
                        size = if (it.isNull(3)) 0 else it.getLong(3),
                        lastModifiedMs = if (it.isNull(4)) 0 else it.getLong(4),
                        isDirectory = it.getString(2) == Document.MIME_TYPE_DIR,
                    )
            }
            children
        }
    }

    private companion object {
        val PROJECTION =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
            )
    }
}

/**
 * Finds documents by [ContentPath], one name at a time, the way a file system would. Listings are cached for 5 s
 * (by [clock], in milliseconds), so the files of one folder cost one query. Thread-safe; listing happens outside the
 * lock, so two threads may list the same folder at once.
 *
 * A name matches exactly first. Failing that, it matches case-insensitively, but only if exactly one name does, as
 * the user's own storage (FAT or exFAT) would.
 */
class DocumentResolver(
    private val lister: DocumentLister,
    private val clock: () -> Long,
) {
    private data class Key(
        val treeUri: String,
        val documentId: String,
    )

    private class Listing(
        val children: List<NativeDocument>,
        val time: Long,
    )

    // In access order, so the eldest is the least recently used.
    private val cache =
        object : LinkedHashMap<Key, Listing>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Listing>) = size > MAX_LISTINGS
        }

    /** The document at [path], or null if there's none. The tree root is a directory with an empty name. */
    fun resolve(path: ContentPath): NativeDocument? {
        var document = NativeDocument("", lister.rootDocumentId(path.treeUri), 0, 0, true)
        for (name in path.segments) {
            if (!document.isDirectory) return null
            document = match(children(path.treeUri, document.documentId) ?: return null, name) ?: return null
        }
        return document
    }

    /** The children of the directory at [path], or null if it isn't one. */
    fun list(path: ContentPath): List<NativeDocument>? {
        val document = resolve(path)?.takeIf { it.isDirectory } ?: return null
        return children(path.treeUri, document.documentId)
    }

    /** Forgets every listing. */
    fun invalidate() {
        synchronized(cache) { cache.clear() }
    }

    private fun children(
        treeUri: String,
        documentId: String,
    ): List<NativeDocument>? {
        val key = Key(treeUri, documentId)
        val now = clock()
        synchronized(cache) {
            val cached = cache[key]
            if (cached != null && now - cached.time < TTL_MS) return cached.children
        }

        val children = lister.listChildren(treeUri, documentId) ?: return null
        synchronized(cache) { cache[key] = Listing(children, now) }
        return children
    }

    private fun match(
        children: List<NativeDocument>,
        name: String,
    ): NativeDocument? {
        children.firstOrNull { it.name == name }?.let { return it }
        val folded = name.lowercase(Locale.ROOT)
        return children.singleOrNull { it.name.lowercase(Locale.ROOT) == folded }
    }

    private companion object {
        const val TTL_MS = 5_000L
        const val MAX_LISTINGS = 64
    }
}
