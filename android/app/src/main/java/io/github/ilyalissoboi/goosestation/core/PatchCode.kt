// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * One of the running game's patch codes: its [index] in the game's list, its [group] (blank for none) and [name] as
 * its file or the database has them, whether it's applied by hand ([manual]) rather than every frame, and whether it's
 * [on]. Made by native code.
 */
class PatchCode(
    val index: Int,
    val group: String,
    val name: String,
    val manual: Boolean,
    val on: Boolean,
)

/** The running game's [codes], and the player's file to add codes in, [fileName] (`<game title>.cht`). */
class PatchCodes(
    val fileName: String,
    val codes: Array<PatchCode>,
)
