// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

/** The Covers page's section of settings.ini, which desktop doesn't read: its dialog keeps nothing. */
private const val COVERS = "Covers"

/**
 * `[Covers] SourceCount` and `Source1` … `Source<N>`: the player's cover sources, URL templates tried in order. Until
 * the list is first saved there's no count, and it's the [PRESET]; a list emptied stays empty.
 */
object CoverSources {
    /** xlenore's PSX cover collection, as its README gives it for downloaders of this kind; it's named by serial. */
    const val PRESET = "https://raw.githubusercontent.com/xlenore/psx-covers/main/covers/default/\${serial}.jpg"

    private const val COUNT = "SourceCount"

    private fun key(number: Int) = "Source$number"

    fun load(s: CoreSettings): List<String> {
        val count = s.getInt(COVERS, COUNT, -1)
        if (count < 0) return listOf(PRESET)
        return (1..count).map { s.getString(COVERS, key(it), "") }.filter { it.isNotBlank() }
    }

    /** Saves [sources], without blank lines, and deletes the keys of any past them. */
    fun save(
        s: CoreSettings,
        sources: List<String>,
    ) {
        val kept = sources.filter { it.isNotBlank() }
        val old = s.getInt(COVERS, COUNT, 0)
        kept.forEachIndexed { index, source -> s.setString(COVERS, key(index + 1), source) }
        for (number in kept.size + 1..old) s.setString(COVERS, key(number), null)
        s.setInt(COVERS, COUNT, kept.size)
    }
}

/** `[Covers] UseSerial`: covers are saved by serial, as the preset names them. On by default. */
object CoverUseSerial {
    private const val KEY = "UseSerial"

    fun load(s: CoreSettings): Boolean = s.getBool(COVERS, KEY, true)

    fun save(
        s: CoreSettings,
        on: Boolean,
    ) = s.setBool(COVERS, KEY, on)
}

private val COVER_VARIABLES = listOf("\${serial}", "\${title}", "\${filetitle}")

/** What a source's row says when it has no variable (it would fetch one image for every game); null when it's fine. */
fun coverSourceProblem(source: String): String? {
    val fine = source.isBlank() || COVER_VARIABLES.any { it in source }
    return if (fine) null else "Needs \${serial}, \${title} or \${filetitle}."
}
