// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/** A [GameEntry] for tests: [title], and whatever else the test is about. */
fun testGame(
    title: String,
    serial: String = "SLUS-00000",
    path: String = "/games/$title.cue",
    played: Long = 0,
    last: Long = 0,
    size: Long = 0,
    uncompressed: Long = 0,
    type: Int = 0,
    discs: List<GameDisc> = emptyList(),
    playlist: Boolean = false,
) = GameEntry(
    path = path,
    title = title,
    serial = serial,
    region = "NTSC-U",
    totalPlayedSeconds = played,
    lastPlayedSeconds = last,
    coverPath = null,
    discs = discs,
    playlist = playlist,
    fileSize = size,
    uncompressedSize = uncompressed,
    typeOrder = type,
)
