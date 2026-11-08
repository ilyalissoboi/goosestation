// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * What a save state shows of itself: its [picture] (null if it has none), and the number of the disc it was made on in
 * the game it holds, 0 when that isn't known. The [serial] and [title] it recorded, and the game it holds: [gamePath]
 * and [gameTitle], the running game's or the library's, empty when neither is its game. Made by native code.
 */
class SaveStatePreview(
    val picture: ArgbImage?,
    val disc: Int,
    val serial: String = "",
    val title: String = "",
    val gamePath: String = "",
    val gameTitle: String = "",
)
