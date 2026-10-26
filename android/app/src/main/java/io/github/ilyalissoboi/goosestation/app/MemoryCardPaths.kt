// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/**
 * Where the memory cards are in the working copy, as the core says ([io.github.ilyalissoboi.goosestation.core.
 * NativeLibrary.getMemoryCardPaths]): relative paths, where one ending in `/` is a folder and the empty one is the
 * whole working copy, and any other one is a card file.
 */
object MemoryCardPaths {
    /** The core's default memory card folder, used until the core says otherwise. */
    val DEFAULT = listOf("memcards/")

    /** Whether the working-copy file at [path] is, or may be, a memory card in [paths]. */
    fun matches(
        path: String,
        paths: List<String>,
    ): Boolean = paths.any { if (it.isEmpty() || it.endsWith('/')) path.startsWith(it) else path == it }
}
