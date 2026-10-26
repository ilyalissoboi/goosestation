// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The system folder picker, which takes a persisted grant on the folder picked: read, plus write for [FolderKind.Data].
 * Construct it in the activity's onCreate, since it registers for the activity's result. [pick] is main thread
 * only.
 */
class FolderPicker(
    activity: ComponentActivity,
) {
    private val resolver = activity.applicationContext.contentResolver
    private var pending: CancellableContinuation<Uri?>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree(), ::onResult)

    /**
     * Opens the picker, and returns the URI of the folder picked once its grant is held. Null if the user backs out,
     * the grant can't be taken, there's no picker, or another pick is still open.
     */
    suspend fun pick(kind: FolderKind): String? {
        if (pending != null) return null
        val uri =
            suspendCancellableCoroutine<Uri?> { continuation ->
                pending = continuation
                continuation.invokeOnCancellation { if (pending === continuation) pending = null }
                try {
                    launcher.launch(null)
                } catch (e: Exception) {
                    // No picker (ActivityNotFoundException), or anything else: either way no result comes, so the
                    // pick ends here rather than leaving every later one refused.
                    Log.e(TAG, "Can't open the system folder picker.", e)
                    pending = null
                    continuation.resume(null)
                }
            } ?: return null
        // A binder call, kept off the main thread.
        return withContext(Dispatchers.IO) { if (takeGrant(uri, kind)) uri.toString() else null }
    }

    /**
     * Releases the grant on a folder the user removed or replaced, unless another slot of [saved] (the folders as they
     * are now) still holds it: one grant per URI serves them all. A binder call: keep it off the main thread.
     */
    fun release(
        uri: String,
        saved: SavedFolders,
    ) {
        if (saved.uses(uri)) return
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), FLAGS_READ_WRITE) }
            .onFailure { Log.w(TAG, "Can't release the grant on $uri.", it) }
    }

    private fun onResult(uri: Uri?) {
        // A result for a pick that was cancelled, or made before the activity was recreated, has no one to go to.
        val continuation = pending ?: return
        pending = null
        if (continuation.isActive) continuation.resume(uri)
    }

    private fun takeGrant(
        uri: Uri,
        kind: FolderKind,
    ): Boolean {
        val flags = if (kind == FolderKind.Data) FLAGS_READ_WRITE else Intent.FLAG_GRANT_READ_URI_PERMISSION
        return runCatching { resolver.takePersistableUriPermission(uri, flags) }
            .onFailure { Log.e(TAG, "Can't keep the grant on $uri.", it) }
            .isSuccess
    }

    private companion object {
        const val TAG = "GooseStation"
        const val FLAGS_READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
