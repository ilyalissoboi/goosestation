// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/**
 * Texture packs are read in place from the data folder's `textures/`, through its grant, and never copied into the
 * app's storage (the mirror leaves them out). Dumps are written to the app's storage and mirrored out.
 */
object TexturePacks {
    /** The folder the core reads packs from: the data folder while its grant is held, else none (""). */
    fun folderFor(
        data: String?,
        granted: Boolean,
    ): String = if (data != null && granted) data else ""
}
