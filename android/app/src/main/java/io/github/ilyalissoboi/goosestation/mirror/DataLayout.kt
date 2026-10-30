// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

/**
 * What the mirror copies between the data folder and the working copy: GooseStation's own data, and nothing else the
 * folder holds (the user's games, say). Paths are relative, with `/` separators.
 *
 * The layout is the top-level files the core keeps, and every file under its folders: the desktop data folder's
 * (`EmuFolders::SetDefaults`, and `emusync/`), plus those the core is configured to use inside the data root, and
 * the shared memory card files, once it reports them ([withCorePaths]). Never mirrored, even inside a layout folder:
 * the core's cache, its log, and the mirror's own temporary files, which stay on the device ([kept]).
 *
 * [fromCore] says whether the core reported the layout. Before it starts, its settings may name folders the defaults
 * lack, so only a layout from the core says which working-copy files are [stray].
 */
class DataLayout private constructor(
    // Each ends in '/'; the empty one is the data root, whose top-level files are then all in the layout.
    private val folders: Set<String>,
    private val files: Set<String>,
    // Each ends in '/'.
    private val caches: Set<String>,
    val fromCore: Boolean,
) {
    /** Whether the file at [path] is mirrored. */
    fun mirrored(path: String) = inLayout(path) && !kept(path)

    /**
     * Whether the working-copy file at [path] is neither mirrored nor the core's: an earlier build copied it in from
     * the data folder, which keeps its own copy.
     */
    fun stray(path: String) = !inLayout(path) && !kept(path)

    /**
     * Whether a listing goes into [folder] (no trailing `/`): it's in a layout folder, or holds one, or a layout file.
     */
    fun walks(folder: String): Boolean {
        val prefix = "$folder/"
        if (caches.any { prefix.startsWith(it) }) return false
        return folders.any { it.isNotEmpty() && (prefix.startsWith(it) || it.startsWith(prefix)) } ||
            files.any { it.startsWith(prefix) }
    }

    /** Whether this layout has a folder or file that [other] lacks, whose files a pull with [other] left out. */
    fun addsTo(other: DataLayout) = !other.folders.containsAll(folders) || !other.files.containsAll(files)

    /**
     * The names among the data folder's top-level [children] (each a name, and whether it's a folder) that aren't
     * GooseStation's, sorted. Hidden names (`.` first, as the mirror's leftovers have) are left out.
     */
    fun others(children: List<Pair<String, Boolean>>): List<String> =
        children
            .filterNot { (name, isDirectory) ->
                name.startsWith('.') || kept(if (isDirectory) "$name/" else name) ||
                    if (isDirectory) walks(name) else inLayout(name)
            }.map { it.first }
            .sorted()

    private fun inLayout(path: String) =
        path in files || folders.any { if (it.isEmpty()) '/' !in path else path.startsWith(it) }

    /** The core's own files that stay on the device: its cache, its log, and the mirror's temporary files. */
    private fun kept(path: String) =
        path == LOG || caches.any { path.startsWith(it) } || path.endsWith(TEMP) || path.endsWith(BACKUP)

    companion object {
        /** The core's log, rewritten at every start: not worth a copy in the data folder. */
        private const val LOG = "goosestation.log"
        private const val TEMP = ".goosetmp"
        private const val BACKUP = ".goosebak"
        private const val CACHE = "cache/"

        private val TOP_FILES = setOf("settings.ini", "secrets.ini", "playtime.dat", "custom_properties.ini")
        private val FOLDERS =
            listOf(
                "bios",
                "cheats",
                "covers",
                "dump",
                "gameicons",
                "gamesettings",
                "inputprofiles",
                "memcards",
                "savestates",
                "screenshots",
                "shaders",
                "textures",
                "resources",
                "videos",
                "emusync",
            ).map { "$it/" }.toSet()

        /** The layout before the core says otherwise: its defaults. */
        val DEFAULT = DataLayout(FOLDERS, TOP_FILES, setOf(CACHE), fromCore = false)

        /**
         * The defaults, plus the [paths] the core reports, relative to the data root: a folder ends in `/` (the empty
         * path is the data root itself), anything else is a file. [cacheFolder] is the core's cache folder, as a
         * folder path, or null if it's outside the data root; `cache/` stays out of the mirror either way.
         */
        fun withCorePaths(
            paths: List<String>,
            cacheFolder: String?,
        ): DataLayout {
            val (folders, files) = paths.partition { it.isEmpty() || it.endsWith('/') }
            val caches = setOf(CACHE) + listOfNotNull(cacheFolder?.takeIf { it.endsWith('/') })
            return DataLayout(FOLDERS + folders, TOP_FILES + files, caches, fromCore = true)
        }
    }
}
