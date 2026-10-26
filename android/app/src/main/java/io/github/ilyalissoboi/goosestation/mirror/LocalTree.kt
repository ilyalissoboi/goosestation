// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** The working copy's side of the mirror: plain files under a root, by relative path with `/` separators. */
object LocalTree {
    private const val TEMP_SUFFIX = ".goosetmp"

    /**
     * Every file under [root], recursively; empty if [root] doesn't exist. The leftover of a [copyIn] that a crash
     * interrupted isn't listed; the next [copyIn] to the same file writes over it.
     */
    fun list(root: File): Map<String, FileState> {
        if (!root.isDirectory) return emptyMap()
        val files = HashMap<String, FileState>()
        for (file in root.walkTopDown()) {
            if (!file.isFile || isTemp(file)) continue
            files[file.relativeTo(root).invariantSeparatorsPath] = FileState(file.length(), file.lastModified())
        }
        return files
    }

    /**
     * Writes [source] to [dest] whole or not at all: to `.<name>.goosetmp` in the same folder, synced, then renamed
     * over [dest]. Creates the missing folders. Doesn't close [source].
     */
    fun copyIn(
        source: InputStream,
        dest: File,
    ) {
        val parent = dest.absoluteFile.parentFile ?: throw IOException("$dest has no folder")
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Can't create $parent")
        val temp = File(parent, ".${dest.name}$TEMP_SUFFIX")
        try {
            FileOutputStream(temp).use { out ->
                source.copyTo(out)
                out.fd.sync()
            }
            if (!temp.renameTo(dest)) throw IOException("Can't rename $temp to $dest")
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    private fun isTemp(file: File) = file.name.startsWith('.') && file.name.endsWith(TEMP_SUFFIX)
}
