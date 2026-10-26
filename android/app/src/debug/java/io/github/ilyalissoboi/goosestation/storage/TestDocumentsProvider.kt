// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * A provider over `filesDir/test-documents`, for the instrumented tests (debug builds only). A document's ID is its
 * path relative to that folder, so each folder in it can be a tree (see TestTrees in androidTest). It has no roots:
 * the system picker never shows it.
 */
class TestDocumentsProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "io.github.ilyalissoboi.goosestation.testdocuments"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_FLAGS)
        private val DEFAULT_DOCUMENT_PROJECTION =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
                Document.COLUMN_FLAGS,
            )

        fun baseDirectory(context: Context) = File(context.filesDir, "test-documents")
    }

    private val base: File by lazy { baseDirectory(context!!).canonicalFile }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<String>?): Cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

    override fun queryDocument(
        documentId: String,
        projection: Array<String>?,
    ): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).also {
            addRow(it, documentId, fileFor(documentId))
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val children = fileFor(parentDocumentId).listFiles() ?: throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).also { cursor ->
            for (child in children) addRow(cursor, "$parentDocumentId/${child.name}", child)
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    // Tree URIs reach only documents under the tree's own.
    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ) = documentId.startsWith("$parentDocumentId/")

    private fun fileFor(documentId: String): File {
        val file = File(base, documentId).canonicalFile
        if (!file.path.startsWith(base.path + File.separator) || !file.exists()) throw FileNotFoundException(documentId)
        return file
    }

    private fun addRow(
        cursor: MatrixCursor,
        documentId: String,
        file: File,
    ) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, 0)
        }
    }
}
