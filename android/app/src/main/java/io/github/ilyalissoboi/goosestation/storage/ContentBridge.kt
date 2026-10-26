// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import java.io.FileNotFoundException

/**
 * Serves the core's content:// paths ([ContentPath]), read only. Called only from content_bridge.cpp, on the core's
 * threads, never on the main thread. Nothing here throws: failures are error returns.
 */
object ContentBridge {
    private const val TAG = "ContentBridge"

    // Negative errno values, as content_bridge.cpp passes them on.
    private const val ENOENT = 2
    private const val EIO = 5
    private const val EACCES = 13

    private class State(
        val resolver: ContentResolver,
        val documents: DocumentResolver,
    )

    @Volatile private var state: State? = null

    /** Call once at startup, before the native library is first used. */
    fun init(context: Context) {
        val resolver = context.applicationContext.contentResolver
        state = State(resolver, DocumentResolver(SafDocumentLister(resolver), SystemClock::elapsedRealtime))
    }

    /** A file descriptor the caller owns, or a negative errno: -ENOENT, -EACCES (no grant), or -EIO. */
    @JvmStatic
    fun openForReading(path: String): Int {
        val state = state ?: return -EIO
        val contentPath = ContentPath.parse(path) ?: return -ENOENT
        return try {
            val document = state.documents.resolve(contentPath) ?: return -ENOENT
            if (document.isDirectory) return -EIO
            val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(contentPath.treeUri), document.documentId)
            state.resolver.openFileDescriptor(uri, "r")?.detachFd() ?: -EIO
        } catch (e: FileNotFoundException) {
            -ENOENT
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to $path", e)
            -EACCES
        } catch (e: Exception) {
            Log.w(TAG, "Can't open $path", e)
            -EIO
        }
    }

    /** `[size, lastModifiedMs, 1 if a directory else 0]`, or null if there's no such document or it can't be read. */
    @JvmStatic
    fun stat(path: String): LongArray? {
        val document = query(path) { documents, contentPath -> documents.resolve(contentPath) } ?: return null
        return longArrayOf(document.size, document.lastModifiedMs, if (document.isDirectory) 1 else 0)
    }

    /** The children of the directory at [path], or null if it isn't a readable directory. */
    @JvmStatic
    fun listDirectory(path: String): Array<NativeDocument>? =
        query(path) { documents, contentPath -> documents.list(contentPath) }?.toTypedArray()

    private fun <T> query(
        path: String,
        block: (DocumentResolver, ContentPath) -> T?,
    ): T? {
        val state = state ?: return null
        val contentPath = ContentPath.parse(path) ?: return null
        return try {
            block(state.documents, contentPath)
        } catch (e: Exception) {
            Log.w(TAG, "Can't read $path", e)
            null
        }
    }
}
