// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.MemcardSyncState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Memory card sync's state as the app shows it: the Memory cards page and the library's badge. [refresh] reads it
 * again through [api] on [io], as sync says it changed; a refresh asked for while one reads reads once more after it,
 * so the last change is never missed. Main thread only.
 */
class MemcardSyncStatus(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val api: CoreApi,
) {
    /** The state, as last read; null until it is. */
    var state by mutableStateOf<MemcardSyncState?>(null)
        private set

    private var reading = false
    private var again = false

    fun refresh() {
        if (reading) {
            again = true
            return
        }
        reading = true
        scope.launch {
            do {
                again = false
                state = withContext(io) { api.getMemcardSyncState() }
            } while (again)
            reading = false
        }
    }
}
