// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

/**
 * Which storage volume a folder or a game is on, from the strings alone: a volume's mount path, as a media broadcast
 * gives it (`/storage/9C33-6BBD`), and the external storage provider's tree document IDs, `<volume ID>:<path>`
 * (`9C33-6BBD:GooseStationGames`, or `primary:…` for the shared storage, which can't be ejected).
 */
object VolumeMatch {
    private const val STORAGE = "/storage/"
    private const val EXTERNAL_STORAGE_TREE = "content://com.android.externalstorage.documents/tree/"

    // Under /storage, but not a removable volume.
    private val notVolumes = setOf("emulated", "self")

    /** `9C33-6BBD` for `/storage/9C33-6BBD`; null for the shared storage or any path that isn't `/storage/<id>`. */
    fun volumeIdOfMountPath(path: String): String? {
        if (!path.startsWith(STORAGE)) return null
        val id = path.substring(STORAGE.length).removeSuffix("/")
        return id.takeUnless { it.isEmpty() || '/' in it || it in notVolumes }
    }

    /**
     * Whether a tree URI, or a content path in one ([ContentPath]), is on the volume [volumeId]: it's the external
     * storage provider's, and its tree document ID starts with `<volumeId>:`, in any case. Other providers' trees
     * never are: they don't go away with a volume's eject.
     */
    fun treeOnVolume(
        treeOrContentPath: String,
        volumeId: String,
    ): Boolean {
        if (!treeOrContentPath.startsWith(EXTERNAL_STORAGE_TREE)) return false
        val treeStart = EXTERNAL_STORAGE_TREE.length
        val treeEnd = treeOrContentPath.indexOf('/', treeStart).let { if (it < 0) treeOrContentPath.length else it }
        // Only the `:` after the volume ID matters, encoded or not.
        val treeId = treeOrContentPath.substring(treeStart, treeEnd).replace("%3A", ":", ignoreCase = true)
        return treeId.startsWith("$volumeId:", ignoreCase = true)
    }
}
