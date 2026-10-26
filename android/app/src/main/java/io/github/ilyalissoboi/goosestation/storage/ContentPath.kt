// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

/**
 * A path the core uses for a document in a granted tree: the tree URI, then `/`-separated display names, such as
 * `content://com.android.externalstorage.documents/tree/primary%3APS1/FF7/disc 1.cue`.
 *
 * The names are raw text, never URL-decoded, so `#`, `%` and `?` in them are only characters. Only [treeUri] is a URI.
 */
data class ContentPath(
    val treeUri: String,
    val segments: List<String>,
) {
    override fun toString(): String =
        if (segments.isEmpty()) treeUri else segments.joinToString("/", prefix = "$treeUri/")

    companion object {
        private const val SCHEME = "content://"
        private const val TREE = "/tree/"

        /**
         * Splits `content://<authority>/tree/<one encoded segment>[/<name>...]`. Empty and `.` names are dropped.
         * Null for `..`, for a path without `/tree/` after the authority, or for an empty tree segment.
         */
        fun parse(path: String): ContentPath? {
            if (!path.startsWith(SCHEME)) return null
            val authorityEnd = path.indexOf('/', SCHEME.length)
            if (authorityEnd <= SCHEME.length || !path.startsWith(TREE, authorityEnd)) return null

            val treeStart = authorityEnd + TREE.length
            val treeEnd = path.indexOf('/', treeStart).let { if (it < 0) path.length else it }
            if (treeEnd == treeStart) return null

            val segments = mutableListOf<String>()
            if (treeEnd < path.length) {
                for (segment in path.substring(treeEnd + 1).split('/')) {
                    when (segment) {
                        "", "." -> continue
                        ".." -> return null
                        else -> segments += segment
                    }
                }
            }
            return ContentPath(path.substring(0, treeEnd), segments)
        }
    }
}
