// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/**
 * Whether the data layout is [settled]: a complete pull found nothing that grows it. Until then, the layout may lack
 * files that settings not yet pulled name (the memory cards of game settings still in the data folder), so the
 * working copy's strays stay. They're kept, never lost, while the folder is away. Any thread.
 */
class LayoutSettling {
    // Counts the growths, so a pull knows whether one came while it ran.
    private var growths = 0

    @get:Synchronized
    var settled = false
        private set

    /** The layout grew, or a catch-up starts: unsettled until a complete pull that starts from now on. */
    @Synchronized
    fun unsettle() {
        growths++
        settled = false
    }

    /** A pull starts: what [pulled] takes once it's done. */
    @Synchronized
    fun pullStarting(): Int = growths

    /**
     * The pull that started at [pull] is done, [complete] if it pulled every file it planned: that settles the layout,
     * unless it grew since the pull started (from the files the pull itself brought in, or a push's).
     */
    @Synchronized
    fun pulled(
        pull: Int,
        complete: Boolean,
    ) {
        if (complete && pull == growths) settled = true
    }
}
