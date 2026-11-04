// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import java.io.File

/**
 * Builds before memory card sync mirrored the data folder's `emusync/` into the working copy. Whatever is there from
 * them came from another install, desktop's say: its sync identity and its unlocked storage key. Sync would take it as
 * this device's, and two devices with one identity overwrite each other's saves without a conflict. [drop] deletes it,
 * once: from then on `emusync/` is this device's own (the mirror keeps it on the device).
 */
object EmusyncCarryOver {
    private const val KEY = "emusyncCarryOverDropped"

    /** Deletes [workingCopy]'s `emusync/` the first time it's called for [store], before the core starts. */
    fun drop(
        workingCopy: File,
        store: KeyValueStore,
    ) {
        if (store.getString(KEY) != null) return
        File(workingCopy, "emusync").deleteRecursively()
        store.putString(KEY, "1")
    }
}
