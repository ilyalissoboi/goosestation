// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

/**
 * What the mirror copies between the data folder and the working copy: GooseStation's own data, and nothing else the
 * folder holds (the user's games, say). Paths are relative, with `/` separators.
 *
 * The layout is the top-level files the core keeps, and every file under its folders: the desktop data folder's
 * (`EmuFolders::SetDefaults`), plus those the core is configured to use inside the data root, and the shared memory
 * card files, once it reports them ([withCorePaths]). Never mirrored, even inside a layout folder: the core's cache,
 * its logs (unless it names them), memory card sync's data (`emusync/`) and scratch files, and the core's and the mirror's own temporary
 * files, which stay on the device ([kept]).
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
    // A desktop's shader packs: its markers' folder (ending in '/'), and the files they list.
    private val packMarkers: String? = null,
    private val packFiles: Set<String> = emptySet(),
) {
    /** Whether the file at [path] is mirrored. */
    fun mirrored(path: String) = inLayout(path) && !kept(path) && !packed(path)

    /**
     * Whether the mirrored file at [path] is only ever sent to the data folder, never taken from it: the core's logs,
     * this device's own, which desktop sharing the folder (and settings.ini's Log to file) writes under the same name,
     * and which the core holds open while it runs.
     */
    fun sentOnly(path: String) = (path == LOG || path == PREVIOUS_LOG) && mirrored(path)

    /**
     * Whether the working-copy file at [path] is neither mirrored nor the core's: an earlier build copied it in from
     * the data folder, which keeps its own copy.
     */
    fun stray(path: String) = (!inLayout(path) || packed(path)) && !kept(path)

    /**
     * This layout, keeping a desktop's shader packs out of the mirror: every file in [markers] (the data folder's
     * `<shaders>/.shaderpacks/`, ending in `/`) and every one of [files] they list. They're strays: a pack lives on
     * each device on its own, and copies an earlier build pulled in are dropped. Android installs its own elsewhere.
     */
    fun withPackFiles(
        markers: String,
        files: Set<String>,
    ) = DataLayout(folders, this.files, caches, fromCore, markers, files)

    private fun packed(path: String) = (packMarkers != null && path.startsWith(packMarkers)) || path in packFiles

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

    /**
     * The core's own files that stay on the device: its cache, its logs (unless the core names them, with Log to file
     * on), the mirror's temporary files, and the core's own temporary save states (written to `<name>.sav.XXXXXX`,
     * then renamed over `<name>.sav`). Memory card sync's data, `emusync/`, which holds this device's sync identity and the storage's key, so a copy elsewhere would give
     * it away or make another device this one; and emusync's scratch files beside a card it's writing. And desktop's
     * secrets.ini, which Android neither reads nor writes (its token is in the Keystore): never copied either way.
     */
    private fun kept(path: String) =
        ((path == LOG || path == PREVIOUS_LOG) && path !in files) || path == SECRETS ||
            caches.any { path.startsWith(it) } ||
            path.endsWith(TEMP) ||
            path.endsWith(BACKUP) || STATE_TEMP.containsMatchIn(path) || path.startsWith(EMUSYNC) ||
            EMUSYNC_SCRATCH.containsMatchIn(path)

    companion object {
        /**
         * The core's log, rewritten at every start, and the last run's, kept by the host as it starts: copied to the
         * data folder only while the core names them, with Log to file on.
         */
        private const val LOG = "goosestation.log"
        private const val PREVIOUS_LOG = "goosestation-previous.log"
        private const val SECRETS = "secrets.ini"
        private const val TEMP = ".goosetmp"
        private const val BACKUP = ".goosebak"
        private const val CACHE = "cache/"
        private const val EMUSYNC = "emusync/"

        // emusync's siblings of a card it's replacing: `<name>.emusync-tmp`, and a folder card's `-old` and `-swap`.
        private val EMUSYNC_SCRATCH = Regex("""\.emusync-(tmp|old|swap)(/|$)""")

        // FileSystem::CreateAtomicRenamedFile()'s name for a save state on its way in: mkstemp()'s six characters.
        private val STATE_TEMP = Regex("""\.sav\.[A-Za-z0-9]{6}$""")

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
