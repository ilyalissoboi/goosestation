// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/**
 * Texture packs are read in place from the data folder's `textures/`, through its grant, and never copied into the
 * app's storage (the mirror leaves them out). Dumps are written to the app's storage and mirrored out.
 */
object TexturePacks {
    /**
     * The folder the core reads packs from: the data folder (none, "", without one). Whether its grant is held
     * doesn't matter: an unreadable folder indexes nothing, and one whose grant comes back is read at the next game's
     * start.
     */
    fun folderFor(data: String?): String = data ?: ""
}
