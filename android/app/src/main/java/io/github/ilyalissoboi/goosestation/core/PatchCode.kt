// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * One of the running game's cheats ("patch codes" in the app's names): its [index] in the game's list, its [group]
 * (blank for none) and [name] as its file or the database has them, whether it's applied by hand ([manual]) rather
 * than every frame, and whether it's [on]. A code read from the newer community format can have values: named
 * options ([optionNames] with [optionValues]), or a range from [rangeMin] to [rangeMax] (none when max is below min),
 * and the [value] in it now. Made by native code.
 */
class PatchCode(
    val index: Int,
    val group: String,
    val name: String,
    val manual: Boolean,
    val on: Boolean,
    val optionNames: Array<String> = emptyArray(),
    val optionValues: IntArray = IntArray(0),
    val rangeMin: Int = 0,
    val rangeMax: Int = -1,
    val value: Int = 0,
)

/**
 * One of the running game's patches (widescreen, frame-rate and display-mode codes): its [index] in the game's patch
 * list, its [group] (blank for none) and [title], its [choices]' names (one for a plain patch), the choice its switches
 * say ([saved]) and the one running now ([running]; -1 is off; they differ until the next start), the values of the
 * choice shown, as [PatchCode] has them, a [note], and whether it can be switched on ([canEnable]). Made by native
 * code.
 */
class GamePatch(
    val index: Int,
    val group: String,
    val title: String,
    val choices: Array<String>,
    val saved: Int,
    val running: Int,
    val optionNames: Array<String> = emptyArray(),
    val optionValues: IntArray = IntArray(0),
    val rangeMin: Int = 0,
    val rangeMax: Int = -1,
    val value: Int = 0,
    val note: String = "",
    val canEnable: Boolean = true,
)

/**
 * The running game's cheats ([codes]) and [patches], and the player's file to add codes in, [fileName]
 * (`<game title>.cht`).
 */
class PatchCodes(
    val fileName: String,
    val codes: Array<PatchCode>,
    val patches: Array<GamePatch> = emptyArray(),
)
