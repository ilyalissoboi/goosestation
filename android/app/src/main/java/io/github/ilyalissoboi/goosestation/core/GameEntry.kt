// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * A game in the core's game list. [path] is what [NativeLibrary.startGame] takes (a content path for a game in a
 * picked folder), [region] is the core's name for it (`NTSC-J`, `NTSC-U`, `PAL`, `Other`), and [coverPath] is a cover
 * image in the working copy, or null if there's none. Made by native code.
 */
data class GameEntry(
    val path: String,
    val title: String,
    val serial: String,
    val region: String,
    val totalPlayedSeconds: Long,
    val lastPlayedSeconds: Long,
    val coverPath: String?,
)
