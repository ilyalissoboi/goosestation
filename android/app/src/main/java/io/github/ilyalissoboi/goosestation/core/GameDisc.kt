// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * One disc of a [GameEntry]: its [path] (for a playlist, the entry it lists), [serial] (blank if the library doesn't
 * know it), [title], and its [number] in the game, as its set, playlist or file name numbers it. Made by native code.
 */
data class GameDisc(
    val path: String,
    val serial: String,
    val title: String,
    val number: Int,
)
