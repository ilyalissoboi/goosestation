// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The one pick under way in a system picker, waiting for its result. [abandon] ends it with nothing, for an activity
 * that goes away before its result comes (a recreated one gets the result, but has no pick to hand it to): the caller
 * would otherwise wait for good. Main thread only.
 */
class PendingPick<T : Any> {
    private var pending: CancellableContinuation<T?>? = null

    /** Whether a pick is under way. */
    val busy: Boolean get() = pending != null

    /**
     * Runs [launch], which opens the picker, and waits for its result; null at once if a pick is under way. A [launch]
     * that can't open one calls [abandon].
     */
    suspend fun await(launch: () -> Unit): T? {
        if (pending != null) return null
        return suspendCancellableCoroutine { continuation ->
            pending = continuation
            continuation.invokeOnCancellation { if (pending === continuation) pending = null }
            launch()
        }
    }

    /** The picker's result, for the pick under way, if any. */
    fun deliver(result: T?) {
        val continuation = pending ?: return
        pending = null
        if (continuation.isActive) continuation.resume(result)
    }

    /** Ends the pick under way with nothing. */
    fun abandon() = deliver(null)
}
