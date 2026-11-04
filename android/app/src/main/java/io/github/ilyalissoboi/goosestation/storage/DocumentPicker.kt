// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Android's file picker for single files: one to read ([open], `ACTION_OPEN_DOCUMENT`) or a new one to write
 * ([create], `ACTION_CREATE_DOCUMENT`), as `content://` documents, with no grant kept past the process. Construct it
 * in the activity's onCreate, since it registers for the activity's results. One pick at a time; [open] and [create]
 * are main thread only.
 */
class DocumentPicker(
    activity: ComponentActivity,
) : Documents {
    private val resolver = activity.applicationContext.contentResolver
    private var pending: CancellableContinuation<Uri?>? = null
    private val opener = activity.registerForActivityResult(ActivityResultContracts.OpenDocument(), ::onResult)
    private val creator =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument(MIME_TYPE), ::onResult)

    override suspend fun open(): PickedDocument? {
        val uri = pick(opener, arrayOf("*/*")) ?: return null
        // A binder call, kept off the main thread.
        return withContext(Dispatchers.IO) { PickedDocument(uri.toString(), displayName(uri)) }
    }

    override suspend fun create(suggestedName: String): String? = pick(creator, suggestedName)?.toString()

    override fun read(
        uri: String,
        limit: Int,
    ): DocumentRead =
        try {
            val input = resolver.openInputStream(Uri.parse(uri)) ?: return DocumentRead.Failed(UNREADABLE)
            input.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > limit) return DocumentRead.TooBig
                }
                DocumentRead.Bytes(out.toByteArray())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't read $uri.", e)
            DocumentRead.Failed(e.localizedMessage ?: UNREADABLE)
        }

    override fun write(
        uri: String,
        bytes: ByteArray,
    ): String? =
        try {
            val output = resolver.openOutputStream(Uri.parse(uri), "wt") ?: return UNWRITABLE
            output.use { it.write(bytes) }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Can't write $uri.", e)
            e.localizedMessage ?: UNWRITABLE
        }

    private suspend fun <I> pick(
        launcher: ActivityResultLauncher<I>,
        input: I,
    ): Uri? {
        if (pending != null) return null
        return suspendCancellableCoroutine { continuation ->
            pending = continuation
            continuation.invokeOnCancellation { if (pending === continuation) pending = null }
            try {
                launcher.launch(input)
            } catch (e: Exception) {
                // No picker (ActivityNotFoundException), or anything else: no result comes.
                Log.e(TAG, "Can't open the system file picker.", e)
                pending = null
                continuation.resume(null)
            }
        }
    }

    private fun onResult(uri: Uri?) {
        // A result for a pick that was cancelled, or made before the activity was recreated, has no one to go to.
        val continuation = pending ?: return
        pending = null
        if (continuation.isActive) continuation.resume(uri)
    }

    // The document's file name, or the last part of its URI without one.
    private fun displayName(uri: Uri): String =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')

    private companion object {
        const val TAG = "DocumentPicker"
        const val MIME_TYPE = "application/octet-stream"
        const val BUFFER = 64 * 1024
        const val UNREADABLE = "The file can't be read."
        const val UNWRITABLE = "The file can't be written."
    }
}
