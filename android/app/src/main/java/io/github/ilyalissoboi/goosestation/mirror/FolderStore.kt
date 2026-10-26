// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import io.github.ilyalissoboi.goosestation.storage.NativeDocument
import io.github.ilyalissoboi.goosestation.storage.SafDocumentLister
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.SyncFailedException

/**
 * The data folder can't be reached at all (no grant, or its provider or storage is gone), or can't take any write
 * (its storage can't rename files); [cause] says why.
 */
class FolderUnreachableException(
    cause: Throwable,
) : IOException(cause.message ?: cause.javaClass.name, cause)

/**
 * The data folder as the mirror sees it: files by relative path with `/` separators.
 *
 * Every method throws [FolderUnreachableException] when the folder can't be reached, and another [IOException] for
 * any other failure, which concerns only the one file (or, from [listAll], only this listing).
 */
interface FolderStore {
    /**
     * Every file in the folder, recursively. The leftovers of an interrupted [replace] are dealt with first, as
     * [recover] does, so a file whose only copy is its `.goosebak` is listed under its own name, never as missing.
     */
    fun listAll(): Map<String, FileState>

    /** Copies the file at [path] over [dest], whole or not at all. */
    fun copyFrom(
        path: String,
        dest: File,
    )

    /**
     * Writes [source] to [path], creating the missing folders, and returns the file's state after the write.
     *
     * [expected] is the state the caller's listing saw at [path], or null if it saw no file there. If the folder's
     * file is no longer as expected (changed, deleted, or new since), or [path] is a folder, nothing is written and
     * this fails: the folder's side wins, at the next run.
     */
    fun replace(
        path: String,
        source: File,
        expected: FileState?,
    ): FileState

    /**
     * Deletes the file at [path], if there is one. As in [replace], it fails, deleting nothing, if the file is no
     * longer in the [expected] state the caller's listing saw.
     */
    fun delete(
        path: String,
        expected: FileState,
    )

    /**
     * Cleans up after interrupted [replace]s in every folder: a `.goosebak` whose file is missing is renamed back to
     * it, any other `.goosebak` is deleted, and so is every `.goosetmp`.
     */
    fun recover()
}

/**
 * A [FolderStore] over a storage access framework tree. Not thread-safe: the mirror runs one operation at a time.
 *
 * [replace] never leaves a half-written file under the real name. It writes `<name>.goosetmp` and syncs it, renames
 * the old `<name>` to `<name>.goosebak`, renames the temporary file to `<name>`, then deletes the `.goosebak`. A crash
 * between any two steps leaves a state [recover] repairs. A provider that can't rename gets no write at all: [replace]
 * fails with a [FolderUnreachableException], so the mirror stops and says so, and the changes stay in the working copy.
 *
 * It keeps the listing of each folder it has seen, and its own changes to them, so an operation costs no extra
 * queries. [listAll] lists afresh; after any failure, every listing is forgotten. That listing only finds documents:
 * whether a file is still as the caller expects is checked against the caller's state, with a query of its own.
 */
class SafFolderStore(
    private val resolver: ContentResolver,
    private val treeUri: String,
) : FolderStore {
    private val tree = Uri.parse(treeUri)
    private val lister = SafDocumentLister(resolver)

    // The children of each folder seen, by name, keyed by the folder's document ID.
    private val folders = HashMap<String, MutableMap<String, NativeDocument>>()

    private class Stat(
        val document: NativeDocument,
        val flags: Int,
    ) {
        val state get() = FileState(document.size, document.lastModifiedMs)
    }

    override fun listAll(): Map<String, FileState> = guard { walk() }

    override fun recover() {
        guard { walk() }
    }

    override fun copyFrom(
        path: String,
        dest: File,
    ) = guard {
        val document = find(path) ?: throw FileNotFoundException("$path isn't in the data folder")
        val input = resolver.openInputStream(uriOf(document.documentId)) ?: throw IOException("Can't open $path")
        input.use { LocalTree.copyIn(it, dest) }
    }

    override fun replace(
        path: String,
        source: File,
        expected: FileState?,
    ): FileState =
        guard {
            val name = path.substringAfterLast('/')
            // A missing folder, when a file was expected in it, means the file is gone.
            val parentId = folderOf(path, create = expected == null) ?: throw changed(path)
            val children = children(parentId)
            // Checked on what is known first, so a mismatch costs no write, and again just before the old file goes.
            requireAsListed(path, children[name], expected)

            // A leftover would make the provider pick another name for the new one. A folder of that name is the
            // user's: the provider names the temporary file otherwise, which is fine.
            children[name + TEMP]?.takeUnless { it.isDirectory }?.let { delete(parentId, it) }
            // Kept under the name the provider gave it: only the rename below must get the name exact.
            val temp = create(parentId, FILE_MIME, name + TEMP, exact = false)
            if (temp.flags and Document.FLAG_SUPPORTS_RENAME == 0) {
                // Written in place, an interrupted write would leave a short file that the next pull takes as the
                // folder's change. Nothing is written: every change stays in the working copy.
                deleteQuietly(parentId, temp.document)
                throw FolderUnreachableException(IOException(CANT_RENAME))
            }
            try {
                write(temp.document, source)
                val old = children[name]
                requireAsListed(path, old?.let(::fresh), expected)
                if (old != null) moveAside(parentId, old, name + BACKUP)
            } catch (e: Exception) {
                deleteQuietly(parentId, temp.document)
                throw e
            }

            val written = rename(parentId, temp.document, name)
            if (written.document.name != name) {
                // Someone else's <name> appeared since the check. Theirs wins, as the folder's side does.
                deleteQuietly(parentId, written.document)
                requireName(written, name)
            }
            // The write is done: a backup left behind is only a leftover for recover().
            children[name + BACKUP]?.takeUnless { it.isDirectory }?.let { deleteQuietly(parentId, it) }
            written.state
        }

    override fun delete(
        path: String,
        expected: FileState,
    ) = guard {
        val parentId = folderOf(path, create = false) ?: return@guard
        val document = children(parentId)[path.substringAfterLast('/')] ?: return@guard
        requireAsListed(path, fresh(document), expected)
        delete(parentId, document)
    }

    /** Renames [old] to [backup], putting it back under its own name if the provider picks another. */
    private fun moveAside(
        parentId: String,
        old: NativeDocument,
        backup: String,
    ) {
        children(parentId)[backup]?.let {
            // The user's folder, not a leftover: it stays, and so does the old file, unreplaced.
            if (it.isDirectory) throw IOException("A folder called $backup is in the way of moving ${old.name} aside")
            delete(parentId, it)
        }
        val moved = rename(parentId, old, backup)
        if (moved.document.name != backup) {
            // Under a name recover() doesn't know, the file would seem gone from the folder.
            try {
                rename(parentId, moved.document, old.name)
            } catch (e: Exception) {
                Log.e(TAG, "Can't rename ${moved.document.name} back to ${old.name}", e)
            }
            requireName(moved, backup)
        }
    }

    /**
     * Lists every folder, recovering each one's leftovers on the way ([recover]), and returns every file in it. A
     * `.goosebak` that can't be renamed back fails the listing: listing without it would read as a deletion.
     */
    private fun walk(): Map<String, FileState> {
        folders.clear()
        val files = HashMap<String, FileState>()
        val pending = ArrayDeque<Pair<String, String>>()
        pending += "" to rootId()
        while (pending.isNotEmpty()) {
            val (folder, folderId) = pending.removeFirst()
            for (child in recovered(folderId).values) {
                val path = if (folder.isEmpty()) child.name else "$folder/${child.name}"
                if (child.isDirectory) {
                    pending += path to child.documentId
                } else {
                    files[path] = FileState(child.size, child.lastModifiedMs)
                }
            }
        }
        return files
    }

    /** The children of [folderId] once its leftovers are dealt with. */
    private fun recovered(folderId: String): Map<String, NativeDocument> {
        val children = children(folderId)
        val leftovers =
            children.values.filter { !it.isDirectory && (it.name.endsWith(TEMP) || it.name.endsWith(BACKUP)) }
        if (leftovers.isEmpty()) return children

        // Backups first: a temporary file isn't a copy to recover, however complete it looks.
        for (leftover in leftovers.sortedBy { it.name.endsWith(TEMP) }) {
            val name = leftover.name.removeSuffix(BACKUP)
            if (leftover.name.endsWith(BACKUP) && name.isNotEmpty() && name !in children) {
                // This relies on the rename keeping the file's time, as file-backed providers do: otherwise the old
                // file looks changed in the folder, and wins over a working-copy change made since.
                Log.w(TAG, "Recovering ${leftover.name} after an interrupted write.")
                requireName(rename(folderId, leftover, name), name)
            } else {
                deleteQuietly(folderId, leftover)
            }
        }
        return list(folderId)
    }

    private fun write(
        document: NativeDocument,
        source: File,
    ) {
        val descriptor =
            resolver.openFileDescriptor(uriOf(document.documentId), "w")
                ?: throw IOException("Can't open ${document.name}")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
            source.inputStream().use { it.copyTo(out) }
            try {
                out.fd.sync()
            } catch (e: SyncFailedException) {
                // A pipe from a provider that isn't backed by a local file: nothing to sync.
                Log.w(TAG, "Can't sync ${document.name}", e)
            }
        }
    }

    /** The document at [path], or null if there's none. */
    private fun find(path: String): NativeDocument? {
        val parentId = folderOf(path, create = false) ?: return null
        return children(parentId)[path.substringAfterLast('/')]?.takeUnless { it.isDirectory }
    }

    /** The document ID of the folder that holds [path], creating it when [create], or null if it's missing. */
    private fun folderOf(
        path: String,
        create: Boolean,
    ): String? {
        var id = rootId()
        for (name in path.split('/').dropLast(1)) {
            val child = children(id)[name]
            id =
                when {
                    child?.isDirectory == true -> child.documentId
                    child != null -> throw IOException("$name, in $path, is a file")
                    !create -> return null
                    else -> createFolder(id, name)
                }
        }
        return id
    }

    /** Creates an empty folder, whose listing is then known without a query. */
    private fun createFolder(
        parentId: String,
        name: String,
    ): String {
        val id = create(parentId, Document.MIME_TYPE_DIR, name).document.documentId
        folders[id] = HashMap()
        return id
    }

    /**
     * Creates a document. When [exact], a [name] the provider changes (because it's taken, say) is an error, and the
     * document is deleted again.
     */
    private fun create(
        parentId: String,
        mime: String,
        name: String,
        exact: Boolean = true,
    ): Stat {
        val uri =
            DocumentsContract.createDocument(resolver, uriOf(parentId), mime, name)
                ?: throw IOException("Can't create $name")
        val created = stat(uri)
        children(parentId)[created.document.name] = created.document
        if (exact && created.document.name != name) {
            deleteQuietly(parentId, created.document)
            throw IOException("The data folder created $name as ${created.document.name}")
        }
        return created
    }

    private fun rename(
        parentId: String,
        document: NativeDocument,
        name: String,
    ): Stat {
        val uri =
            DocumentsContract.renameDocument(resolver, uriOf(document.documentId), name)
                ?: throw IOException("Can't rename ${document.name} to $name")
        val children = children(parentId)
        children.remove(document.name)
        val renamed = stat(uri)
        children[renamed.document.name] = renamed.document
        return renamed
    }

    /**
     * Fails unless [document] (null for none) is in the [expected] state the caller's listing saw: someone (the
     * user, copying a memory card in from a PC) may have changed, deleted or created it since. The folder's side
     * then wins at the next run.
     */
    private fun requireAsListed(
        path: String,
        document: NativeDocument?,
        expected: FileState?,
    ) {
        if (document?.isDirectory == true) throw IOException("$path is a folder in the data folder")
        if (document?.let { FileState(it.size, it.lastModifiedMs) } != expected) throw changed(path)
    }

    private fun changed(path: String) = IOException("$path changed in the data folder since it was listed")

    /** [document] as the provider has it now. */
    private fun fresh(document: NativeDocument) = stat(uriOf(document.documentId)).document

    /** Fails unless [stat] has [name]: a provider may pick another name when the one asked for is taken. */
    private fun requireName(
        stat: Stat,
        name: String,
    ) {
        if (stat.document.name != name) throw IOException("The data folder gave $name the name ${stat.document.name}")
    }

    private fun delete(
        parentId: String,
        document: NativeDocument,
    ) {
        if (!DocumentsContract.deleteDocument(resolver, uriOf(document.documentId))) {
            throw IOException("Can't delete ${document.name}")
        }
        children(parentId).remove(document.name)
        folders.remove(document.documentId)
    }

    /** Deletes a leftover; failing to is only logged, as the next [recover] tries again. */
    private fun deleteQuietly(
        parentId: String,
        document: NativeDocument,
    ) {
        try {
            delete(parentId, document)
        } catch (e: Exception) {
            // ExternalStorageProvider throws IllegalStateException for a file it can't delete, for one.
            Log.w(TAG, "Can't delete ${document.name}", e)
        }
    }

    private fun stat(uri: Uri): Stat {
        val cursor = resolver.query(uri, STAT_PROJECTION, null, null, null) ?: throw FileNotFoundException("$uri")
        return cursor.use {
            if (!it.moveToFirst()) throw FileNotFoundException("$uri")
            Stat(
                NativeDocument(
                    name = it.getString(1),
                    documentId = it.getString(0),
                    size = if (it.isNull(3)) 0 else it.getLong(3),
                    lastModifiedMs = if (it.isNull(4)) 0 else it.getLong(4),
                    isDirectory = it.getString(2) == Document.MIME_TYPE_DIR,
                ),
                if (it.isNull(5)) 0 else it.getInt(5),
            )
        }
    }

    private fun children(folderId: String): MutableMap<String, NativeDocument> = folders[folderId] ?: list(folderId)

    private fun list(folderId: String): MutableMap<String, NativeDocument> {
        val listing = lister.listing(treeUri, folderId) ?: throw FileNotFoundException("Can't list $folderId")
        // Maybe only some of the files: the missing ones would read as deletions.
        if (listing.loading) throw IOException("The data folder's provider is still loading $folderId")
        val children = HashMap<String, NativeDocument>()
        for (child in listing.children) children.putIfAbsent(child.name, child)
        folders[folderId] = children
        return children
    }

    private fun rootId() = DocumentsContract.getTreeDocumentId(tree)

    private fun uriOf(documentId: String) = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    private fun rootReachable() =
        try {
            resolver.query(uriOf(rootId()), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
                it.moveToFirst()
            } == true
        } catch (e: Exception) {
            false
        }

    /**
     * Runs [block], turning anything it throws into a [FolderUnreachableException] when the root can't be queried
     * either (no grant, the storage removed), and otherwise into an [IOException] for this one operation. Providers
     * throw [IllegalStateException] and the like for one file they can't handle, too.
     */
    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            folders.clear()
            throw when {
                e is FolderUnreachableException -> e
                !rootReachable() -> FolderUnreachableException(e)
                e is IOException -> e
                else -> IOException(e)
            }
        }

    private companion object {
        const val TAG = "SafFolderStore"
        const val TEMP = ".goosetmp"
        const val BACKUP = ".goosebak"
        const val FILE_MIME = "application/octet-stream"
        const val CANT_RENAME = "its storage can't replace files safely"

        val STAT_PROJECTION =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
                Document.COLUMN_FLAGS,
            )
    }
}
