// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

import kotlinx.coroutines.flow.StateFlow

/**
 * The runs of a mirror of the data folder ([Mirror]), as the app drives them: what a JVM test fakes to check what the
 * app decides, without copying files.
 */
interface MirrorRuns {
    /** What the mirror is doing. */
    val state: StateFlow<MirrorState>

    /** Folder to working copy, except the [deferred] paths. */
    suspend fun pull(deferred: (String) -> Boolean): PullResult

    /** Working copy to folder, except the [deferred] paths, without waiting for files to settle when [force]. */
    suspend fun push(
        force: Boolean,
        deferred: (String) -> Boolean,
    ): PushResult

    /** Ends a hold: the next run tries the folder again. */
    fun letGo()
}
