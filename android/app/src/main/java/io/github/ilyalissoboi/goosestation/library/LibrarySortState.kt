// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The library's [sort]: settings.ini's, as the core reads it ([read]), or the player's [pick], which [save] writes to
 * settings.ini. A pick made before the core has started is the player's latest word, so it wins over the file: it's
 * kept through the core's first read and saved once the core runs ([coreStarted]). A later read, of a settings.ini
 * pulled from another device, gives its sort as usual. Main thread only.
 */
class LibrarySortState(
    private val save: (LibrarySort) -> Unit,
) {
    var sort by mutableStateOf(LibrarySort())
        private set
    private var coreRunning = false

    // Picked before the core could save it.
    private var unsaved = false

    /** Sorts by [sort], saving it; nothing when it's the sort already, so the keys are only written once it changes. */
    fun pick(sort: LibrarySort) {
        if (sort == this.sort) return
        this.sort = sort
        if (coreRunning) save(sort) else unsaved = true
    }

    /** settings.ini's sort, as the core has read it: the sort, unless a pick is waiting to be saved. */
    fun read(stored: LibrarySort) {
        if (!unsaved) sort = stored
    }

    /** The core has started: a pick waiting since before then is saved. */
    fun coreStarted() {
        coreRunning = true
        if (!unsaved) return
        unsaved = false
        save(sort)
    }
}
