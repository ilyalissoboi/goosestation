// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import io.github.ilyalissoboi.goosestation.storage.decodeDocumentId

/** A game file's name, from its path: a content URI's document, or a plain path's last part. */
object GameFiles {
    private const val DOCUMENT = "/document/"

    /** The file's name with its extension: a content URI's from its decoded document ID, after the ID's last `/`. */
    fun fileName(path: String): String {
        val document = path.indexOf(DOCUMENT).takeIf { path.startsWith("content://") && it >= 0 }
        val name =
            document?.let { decodeDocumentId(path.substring(it + DOCUMENT.length)) } ?: path
        return name.substringAfterLast('/')
    }

    /** The file's name up to its last `.`, as the core's `Path::GetFileTitle` takes it. */
    fun fileTitle(path: String): String = fileName(path).substringBeforeLast('.')
}
