// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * A game in the library: one file, or a multi-disc game's discs grouped. [path] is what [NativeLibrary.startGame] takes
 * (a content path for a game in a picked folder): a playlist's, its first disc's, or the file's. [serial] is **the
 * game's key**, which its save states are kept under: a disc set's first serial, a playlist's first entry's, a name
 * group's lowest disc's, or a single file's own (the core's content hash, `HASH-…`, for one without a serial). [region]
 * is the core's name for it (`NTSC-J`, `NTSC-U`, `PAL`, `Other`), and [coverPath] is a cover image in the working
 * copy, or null if there's none. The play time is summed over its discs.
 *
 * [discs] are its discs in number order, one for a single disc and none for a PS-EXE or a PSF: a game is multi-disc
 * when it has more than one. A [playlist] switches between them as the sub-images of its M3U. Made by native code.
 */
data class GameEntry(
    val path: String,
    val title: String,
    val serial: String,
    val region: String,
    val totalPlayedSeconds: Long,
    val lastPlayedSeconds: Long,
    val coverPath: String?,
    val discs: List<GameDisc> = emptyList(),
    val playlist: Boolean = false,
) {
    /** For native code, which passes the discs as an array. */
    constructor(
        path: String,
        title: String,
        serial: String,
        region: String,
        totalPlayedSeconds: Long,
        lastPlayedSeconds: Long,
        coverPath: String?,
        discs: Array<GameDisc>,
        playlist: Boolean,
    ) : this(path, title, serial, region, totalPlayedSeconds, lastPlayedSeconds, coverPath, discs.toList(), playlist)
}
