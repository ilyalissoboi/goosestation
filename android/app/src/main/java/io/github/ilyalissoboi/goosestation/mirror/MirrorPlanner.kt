// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

/** One step of a mirror run, on a relative path with `/` separators. */
sealed interface MirrorAction {
    val path: String
}

/** Copy the data folder's file over the working copy's. */
data class CopyToLocal(
    override val path: String,
) : MirrorAction

/** Copy the working copy's file over the data folder's. */
data class CopyToFolder(
    override val path: String,
) : MirrorAction

/** Delete the working copy's file: the data folder deleted it. */
data class DeleteLocal(
    override val path: String,
) : MirrorAction

/** Delete the data folder's file: the working copy deleted it. */
data class DeleteFromFolder(
    override val path: String,
) : MirrorAction

/** Drop the manifest entry: the file is gone on both sides. */
data class Forget(
    override val path: String,
) : MirrorAction

/**
 * Decides what a mirror run does, from the data folder's and the working copy's listings and the [Manifest]. It does
 * no I/O. The data folder is the source of truth: when both sides changed, or one changed and the other deleted, the
 * folder's side wins, with no conflict detection.
 *
 * `deferred(path)` is true for the files that mustn't be replaced in the working copy right now (a running game's
 * memory cards). Nothing replaces or deletes a deferred working-copy file, and no push sends a deferred file whose
 * folder side won: it is left for a later run.
 *
 * Plans are sorted by path, and hold only the paths the [DataLayout] mirrors: anything else on either side is never
 * pulled, pushed or deleted.
 */
object MirrorPlanner {
    /** How long a working-copy file must stay unchanged before a push sends it, so a file isn't sent mid-write. */
    const val SETTLE_MS = 2000L

    /**
     * Folder to working copy. A folder file that is new or changed is copied in, even over a changed local file. A
     * file the folder deleted is deleted locally, or forgotten if it is gone locally too. A local file the manifest
     * doesn't know is left for the next push.
     */
    fun pull(
        folder: Map<String, FileState>,
        local: Map<String, FileState>,
        manifest: Manifest,
        layout: DataLayout,
        deferred: (String) -> Boolean,
    ): List<MirrorAction> =
        paths(folder.keys, manifest.entries.keys, layout).mapNotNull { path ->
            if (deferred(path)) return@mapNotNull null
            folderWins(path, folder[path], local[path], manifest[path])
        }

    /**
     * Working copy to folder. A local file that is new or changed, and has been unchanged for [SETTLE_MS] (not
     * waited for when [force], nor when its time is in the future), is copied out. A file the working copy deleted
     * is deleted from the folder. Where the folder's side is new, changed or deleted as well, the folder wins
     * instead, as in a [pull], with no wait.
     */
    fun push(
        folder: Map<String, FileState>,
        local: Map<String, FileState>,
        manifest: Manifest,
        layout: DataLayout,
        nowMs: Long,
        force: Boolean,
        deferred: (String) -> Boolean,
    ): List<MirrorAction> =
        paths(local.keys, manifest.entries.keys, layout).mapNotNull { path ->
            val f = folder[path]
            val l = local[path]
            val m = manifest[path]
            when {
                l == m?.local -> null
                folderChanged(f, m) -> if (deferred(path)) null else folderWins(path, f, l, m)
                // Gone locally, so the path came from the manifest, and the folder still holds its file unchanged.
                l == null -> DeleteFromFolder(path)
                force || settled(l, nowMs) -> CopyToFolder(path)
                else -> null
            }
        }

    /** The folder side's action, for a path the folder or the manifest holds: null when the folder is unchanged. */
    private fun folderWins(
        path: String,
        f: FileState?,
        l: FileState?,
        m: ManifestEntry?,
    ): MirrorAction? =
        when {
            !folderChanged(f, m) -> null
            f != null -> CopyToLocal(path)
            l != null -> DeleteLocal(path)
            else -> Forget(path)
        }

    /**
     * True when [l] has been unchanged for [SETTLE_MS]. A time in the future (the clock went back, or the file was
     * copied in with its own time) counts as settled, or the file would wait until the clock caught up.
     */
    private fun settled(
        l: FileState,
        nowMs: Long,
    ): Boolean {
        val age = nowMs - l.lastModifiedMs
        return age < 0 || age >= SETTLE_MS
    }

    /** True when the folder's file is new, changed or deleted since the manifest's entry. */
    private fun folderChanged(
        f: FileState?,
        m: ManifestEntry?,
    ) = f != m?.folder

    private fun paths(
        a: Set<String>,
        b: Set<String>,
        layout: DataLayout,
    ) = (a + b).filter(layout::mirrored).sorted()
}
