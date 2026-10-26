// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The system folder picker, which takes a persisted grant on the folder picked: read, plus write for [FolderKind.Data].
 * Construct it in the activity's onCreate, since it registers for the activity's result. Main thread only.
 */
class FolderPicker(
    activity: ComponentActivity,
) {
    private val resolver = activity.applicationContext.contentResolver
    private var kind: FolderKind? = null
    private var pending: CancellableContinuation<String?>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree(), ::onResult)

    /**
     * Opens the picker, and returns the URI of the folder picked once its grant is held. Null if the user backs out,
     * the grant can't be taken, there's no picker, or another pick is still open.
     */
    suspend fun pick(kind: FolderKind): String? {
        if (pending != null) return null
        return suspendCancellableCoroutine { continuation ->
            this.kind = kind
            pending = continuation
            continuation.invokeOnCancellation { if (pending === continuation) clear() }
            try {
                launcher.launch(null)
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "There's no system folder picker.", e)
                clear()
                continuation.resume(null)
            }
        }
    }

    /** Releases the grant on a folder the user removed. */
    fun release(uri: String) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), FLAGS_READ_WRITE) }
            .onFailure { Log.w(TAG, "Can't release the grant on $uri.", it) }
    }

    private fun onResult(uri: Uri?) {
        // A result for a pick that was cancelled, or made before the activity was recreated, has no one to go to.
        val continuation = pending ?: return
        val pickedKind = kind
        clear()
        val picked = uri?.let { if (takeGrant(it, pickedKind)) it.toString() else null }
        if (continuation.isActive) continuation.resume(picked)
    }

    private fun takeGrant(
        uri: Uri,
        kind: FolderKind?,
    ): Boolean {
        val flags = if (kind == FolderKind.Data) FLAGS_READ_WRITE else Intent.FLAG_GRANT_READ_URI_PERMISSION
        return runCatching { resolver.takePersistableUriPermission(uri, flags) }
            .onFailure { Log.e(TAG, "Can't keep the grant on $uri.", it) }
            .isSuccess
    }

    private fun clear() {
        kind = null
        pending = null
    }

    private companion object {
        const val TAG = "GooseStation"
        const val FLAGS_READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
