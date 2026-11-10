// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.update

/**
 * GooseStation's versions, as `git describe --dirty` prints them: `MAJOR.MINOR`, the commits since that tag and the
 * commit (`0.1-574-gec4c2bf2e`), and `-dirty` for a build with uncommitted changes. A release's version code comes from
 * it ([code]), as android/app/build.gradle.kts makes it: keep the two in step.
 */
object AppVersion {
    data class Parsed(
        val major: Int,
        val minor: Int,
        val count: Int,
        val hash: String?,
        val dirty: Boolean,
    )

    private val PATTERN = Regex("""^(\d+)\.(\d+)(?:-(\d+)-g([0-9a-f]+))?(-dirty)?$""")

    // The limits that keep a code under Android's 2,100,000,000.
    private const val MAX_MAJOR = 20
    private const val MAX_MINOR = 999
    private const val MAX_COUNT = 99_999

    /** [name]'s parts, or null when it isn't a described version. */
    fun parse(name: String): Parsed? {
        val match = PATTERN.matchEntire(name) ?: return null
        val (major, minor, count, hash, dirty) = match.destructured
        return Parsed(
            major = major.toIntOrNull() ?: return null,
            minor = minor.toIntOrNull() ?: return null,
            count = if (count.isEmpty()) 0 else count.toIntOrNull() ?: return null,
            hash = hash.ifEmpty { null },
            dirty = dirty.isNotEmpty(),
        )
    }

    /**
     * The version code for [name]: `(MAJOR * 1000 + MINOR) * 100000 + count`, so each commit's is higher than the last,
     * and a new tag's higher than any before it. Null when [name] doesn't parse or is past the limits.
     */
    fun code(name: String): Int? {
        val v = parse(name) ?: return null
        if (v.major > MAX_MAJOR || v.minor > MAX_MINOR || v.count > MAX_COUNT) return null
        return (v.major * 1000 + v.minor) * 100_000 + v.count
    }

    /** [name] without its commit and `-dirty`, as the app shows a version: `0.1-512`. Unparseable, [name] itself. */
    fun short(name: String): String {
        val v = parse(name) ?: return name
        return if (v.hash == null) "${v.major}.${v.minor}" else "${v.major}.${v.minor}-${v.count}"
    }
}
