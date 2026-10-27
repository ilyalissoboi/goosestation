// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

/** One mirrored file: its working-copy and data-folder states as they were when the two were last known to match. */
data class ManifestEntry(
    val local: FileState,
    val folder: FileState,
)

/**
 * What the mirror last saw on both sides, keyed by relative path with `/` separators. A side "changed" when its
 * current state differs from the entry's.
 *
 * Immutable: [with] and [without] return a changed copy.
 */
class Manifest(
    entries: Map<String, ManifestEntry>,
) {
    val entries: Map<String, ManifestEntry> = entries.toMap()

    init {
        require(this.entries.keys.none { it.isEmpty() }) { "a manifest path is empty" }
    }

    operator fun get(path: String): ManifestEntry? = entries[path]

    fun with(
        path: String,
        entry: ManifestEntry,
    ) = Manifest(entries + (path to entry))

    fun without(path: String) = Manifest(entries - path)

    /** The text form: a header line, then `path, local size, local time, folder size, folder time` per line. */
    fun encode(): String =
        buildString {
            append(HEADER).append('\n')
            for ((path, entry) in entries.toSortedMap()) {
                append(escape(path))
                append('\t').append(entry.local.size).append('\t').append(entry.local.lastModifiedMs)
                append('\t').append(entry.folder.size).append('\t').append(entry.folder.lastModifiedMs)
                append('\n')
            }
        }

    override fun equals(other: Any?) = other is Manifest && other.entries == entries

    override fun hashCode() = entries.hashCode()

    override fun toString() = "Manifest($entries)"

    companion object {
        private const val HEADER = "goosestation-mirror 1"

        /** The manifest [text] holds, or null if any line of it is malformed; the caller then starts empty. */
        fun decode(text: String): Manifest? {
            val lines = text.removeSuffix("\n").split('\n')
            if (lines.first() != HEADER) return null
            val entries = HashMap<String, ManifestEntry>()
            for (line in lines.drop(1)) {
                val fields = line.split('\t')
                if (fields.size != 5) return null
                val path = unescape(fields[0])?.takeIf { it.isNotEmpty() } ?: return null
                val n = fields.drop(1).map { it.toLongOrNull() ?: return null }
                if (entries.put(path, ManifestEntry(FileState(n[0], n[1]), FileState(n[2], n[3]))) != null) return null
            }
            return Manifest(entries)
        }

        private fun escape(path: String) =
            buildString {
                for (c in path) {
                    when (c) {
                        '\\' -> append("\\\\")
                        '\t' -> append("\\t")
                        '\n' -> append("\\n")
                        else -> append(c)
                    }
                }
            }

        /** [escape] undone, or null for a `\` that isn't followed by `\`, `t` or `n`. */
        private fun unescape(field: String): String? {
            val out = StringBuilder(field.length)
            var i = 0
            while (i < field.length) {
                val c = field[i++]
                if (c != '\\') {
                    out.append(c)
                    continue
                }
                when (field.getOrNull(i++)) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    else -> return null
                }
            }
            return out.toString()
        }
    }
}
