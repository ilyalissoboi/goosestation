// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * A BIOS image among the app's copies, in filesDir/bios: its [fileName] there, the core's [description] of it (such
 * as `SCPH-5500 (v3.0 09-09-96 J)`), and the console [region] it's made for, as the core names it (`NTSC-U`,
 * `NTSC-J` or `PAL`), or `Auto` for one good for any. Made by native code.
 */
data class BiosImage(
    val fileName: String,
    val description: String,
    val region: String,
)
