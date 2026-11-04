// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/**
 * Whether memory card sync should rest, from the app's visibility, told through [tell] (`true`: in the background)
 * only once the core runs, and only when it changes. Coming back, sync resumes once the pull is done ([pullDone]), and
 * only if the app is still in front then. The core starts in the foreground, so a start while hidden tells it so.
 * Main thread only.
 */
class SyncVisibility(
    private val tell: (backgrounded: Boolean) -> Unit,
) {
    private var visible = true
    private var coreRunning = false
    private var told = false

    /** The core has started. */
    fun coreStarted() {
        coreRunning = true
        set(!visible)
    }

    /** The app is in front again; sync waits for [pullDone]. */
    fun started() {
        visible = true
    }

    /** The pull on the way back is done, however it ended. */
    fun pullDone() {
        if (visible) set(false)
    }

    /** The app went to the background, after the pause's card writes. */
    fun stopped() {
        visible = false
        set(true)
    }

    private fun set(backgrounded: Boolean) {
        if (!coreRunning || told == backgrounded) return
        told = backgrounded
        tell(backgrounded)
    }
}
