// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A provider over `filesDir/test-documents`, for the instrumented tests (debug builds only). A document's ID is its
 * path relative to that folder, so each folder in it can be a tree (see TestTrees in androidTest). It has no roots:
 * the system picker never shows it.
 *
 * Writable like the platform's ExternalStorageProvider: [createDocument] and [renameDocument] pick a unique name
 * (`name (1).ext`) when the one asked for is taken.
 *
 * Tests can make it misbehave as real providers do: refuse the documents named in [refused], and list the folders
 * named in [loading] as a cloud provider does while it is still fetching them. They can also see what was asked of
 * it: the documents [opened] and the folders [listed].
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

        private const val FILE_FLAGS =
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        private const val DIRECTORY_FLAGS =
            Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME

        fun baseDirectory(context: Context) = File(context.filesDir, "test-documents")

        /** Names of documents that every call fails on, with an IllegalStateException; a test sets it. */
        @Volatile
        var refused: Set<String> = emptySet()

        /**
         * Names of folders listed as a cloud provider lists one it is still fetching, marked
         * [DocumentsContract.EXTRA_LOADING]: their subfolders so far, and none of their files. A test sets it.
         */
        @Volatile
        var loading: Set<String> = emptySet()

        /** The ID of every document [openDocument] was called for, in order; a test clears it. */
        val opened = ConcurrentLinkedQueue<String>()

        /** The ID of every folder [queryChildDocuments] was called for, in order; a test clears it. */
        val listed = ConcurrentLinkedQueue<String>()
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
        listed += parentDocumentId
        val parent = fileFor(parentDocumentId)
        val children = parent.listFiles() ?: throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).also { cursor ->
            val loading = parent.name in loading
            if (loading) cursor.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            for (child in children) {
                if (!loading || child.isDirectory) addRow(cursor, "$parentDocumentId/${child.name}", child)
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        opened += documentId
        return ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) throw IllegalArgumentException("$parentDocumentId isn't a directory")
        refuse(displayName)
        val file = uniqueFile(parent, displayName)
        val created = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!created) throw IllegalStateException("Can't create $file")
        return "$parentDocumentId/${file.name}"
    }

    override fun renameDocument(
        documentId: String,
        displayName: String,
    ): String {
        val file = fileFor(documentId)
        refuse(displayName)
        val target = uniqueFile(file.parentFile!!, displayName)
        if (!file.renameTo(target)) throw IllegalStateException("Can't rename $documentId to $displayName")
        return "${documentId.substringBeforeLast('/')}/${target.name}"
    }

    override fun deleteDocument(documentId: String) {
        if (!fileFor(documentId).deleteRecursively()) throw IllegalStateException("Can't delete $documentId")
    }

    // Tree URIs reach only documents under the tree's own.
    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ) = documentId.startsWith("$parentDocumentId/")

    private fun fileFor(documentId: String): File {
        if (disabled()) throw FileNotFoundException("The provider is disabled")
        val file = File(base, documentId).canonicalFile
        if (!file.path.startsWith(base.path + File.separator) || !file.exists()) throw FileNotFoundException(documentId)
        refuse(file.name)
        return file
    }

    private fun refuse(name: String) {
        if (name in refused) throw IllegalStateException("$name is refused")
    }

    /**
     * True while TestTrees has the provider disabled. The app's own process keeps using a provider it has used once,
     * disabled or not, so the provider fails its calls itself, as a provider whose storage was removed does.
     */
    private fun disabled(): Boolean {
        val context = context!!
        val component = ComponentName(context, TestDocumentsProvider::class.java)
        return context.packageManager.getComponentEnabledSetting(component) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun uniqueFile(
        parent: File,
        name: String,
    ): File {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var file = File(parent, name)
        var n = 1
        while (file.exists()) file = File(parent, "$stem (${n++})$extension")
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
            add(Document.COLUMN_FLAGS, if (file.isDirectory) DIRECTORY_FLAGS else FILE_FLAGS)
        }
    }
}
