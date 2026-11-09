// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/** What a cover download reports as it goes, on the thread it runs on; [cancelled] stops it, mid-request too. */
interface CoverProgress {
    /** The game at [position] (from 1) of [total] is being fetched. */
    fun game(
        position: Int,
        total: Int,
        title: String,
    )

    /** The tile at [gamePath] has a cover now, at [coverPath]. */
    fun found(
        gamePath: String,
        coverPath: String,
    )

    fun cancelled(): Boolean
}

/**
 * How a download went: of the [total] tiles without a cover at its start, how many got one, had none at the sources
 * ([missing]), had no source answer at all ([unreachable]), or got one that couldn't be saved ([unsaved]); and the
 * library's [games], all of them.
 */
data class CoverSummary(
    val total: Int,
    val found: Int,
    val missing: Int,
    val unreachable: Int,
    val unsaved: Int,
    val cancelled: Boolean,
    val games: Int = total,
)

/** Cover downloads for the library's tiles. Blocks (the network): never on the main or the core's thread. */
interface CoverApi {
    /** Fetches a cover for each tile without one, from [sources] in order, saved by serial when [useSerial]. */
    fun downloadCovers(
        sources: List<String>,
        useSerial: Boolean,
        progress: CoverProgress,
    ): CoverSummary
}

/** [CoverApi] through the core. */
object NativeCoverApi : CoverApi {
    override fun downloadCovers(
        sources: List<String>,
        useSerial: Boolean,
        progress: CoverProgress,
    ): CoverSummary =
        NativeLibrary.downloadCovers(sources.toTypedArray(), useSerial, progress).let {
            CoverSummary(it[0], it[1], it[2], it[3], it[4], it[5] == 1, it[6])
        }
}
