// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.update

import io.github.ilyalissoboi.goosestation.storage.KeyValueStore

/**
 * The update check's own choices, kept with the app (SharedPreferences `updates`), never in settings.ini: skipping a
 * version here doesn't skip it on desktop or in the other app.
 */
class UpdatePrefs(
    private val store: KeyValueStore,
) {
    /** The commit of the version "Skip this version" skipped, or null. */
    fun skippedCommit(): String? = store.getString(SKIPPED_COMMIT)

    fun skip(commit: String) {
        store.putString(SKIPPED_COMMIT, commit)
    }

    companion object {
        const val NAME = "updates"
        private const val SKIPPED_COMMIT = "skippedCommit"
    }
}
