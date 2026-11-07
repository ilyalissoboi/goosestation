// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

/**
 * The shader browser's folders and search, over the offered shaders: the core's PostProcessing::Browser rules
 * (desktop's Qt and Big Picture browsers use those). Folders are '/'-separated browser paths; "" is the top.
 */
object ShaderBrowser {
    /** A folder's child folders, sorted ignoring case, and the shaders directly in it, in the list's order. */
    data class Listing(
        val folders: List<String>,
        val shaders: List<Shader>,
    )

    /** Where [shader] sits in the browser: `reshade/<name>` for a ReShade shader, else its name. */
    fun path(shader: Shader): String = if (shader.kind == ShaderKind.ReShade) "reshade/${shader.name}" else shader.name

    fun list(
        shaders: List<Shader>,
        folder: String,
    ): Listing {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val folders = linkedSetOf<String>()
        val inside = mutableListOf<Shader>()
        for (shader in shaders) {
            val path = path(shader)
            if (!path.startsWith(prefix) || path.length == prefix.length) continue
            val rest = path.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash < 0) inside += shader else folders += rest.substring(0, slash)
        }
        return Listing(folders.sortedWith(String.CASE_INSENSITIVE_ORDER), inside)
    }

    /** Every shader whose path contains the trimmed [text], ignoring case; none for no text. */
    fun search(
        shaders: List<Shader>,
        text: String,
    ): List<Shader> {
        val needle = text.trim()
        if (needle.isEmpty()) return emptyList()
        return shaders.filter { path(it).contains(needle, ignoreCase = true) }
    }

    /** The folder above, "" for the top. */
    fun parent(folder: String): String = folder.substringBeforeLast('/', "")

    /** The folder [shader] is in. */
    fun folderOf(shader: Shader): String = parent(path(shader))

    /** The shader's own name in its folder, without `.slangp`. */
    fun leaf(shader: Shader): String {
        val leaf = path(shader).substringAfterLast('/')
        return if (leaf.length > ".slangp".length && leaf.endsWith(".slangp", ignoreCase = true)) {
            leaf.dropLast(".slangp".length)
        } else {
            leaf
        }
    }

    /** The folder to open on: [last] if it holds anything, else its nearest folder that does, else the top. */
    fun open(
        shaders: List<Shader>,
        last: String,
    ): String {
        var folder = last
        while (folder.isNotEmpty()) {
            val listing = list(shaders, folder)
            if (listing.folders.isNotEmpty() || listing.shaders.isNotEmpty()) return folder
            folder = parent(folder)
        }
        return folder
    }
}
