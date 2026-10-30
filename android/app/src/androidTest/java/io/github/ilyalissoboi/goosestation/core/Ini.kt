// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/** As much of the INI format as the core writes: `[Section]` and `Key = Value` lines, a key once per list item. */
class Ini(
    private val sections: Map<String, List<Pair<String, String>>>,
) {
    fun values(
        section: String,
        key: String,
    ): List<String> = sections[section].orEmpty().filter { it.first == key }.map { it.second }

    fun value(
        section: String,
        key: String,
    ): String? = values(section, key).singleOrNull()

    fun allValues(): List<String> = sections.values.flatten().map { it.second }

    override fun toString() = sections.toString()

    companion object {
        fun parse(text: String): Ini {
            val sections = mutableMapOf<String, MutableList<Pair<String, String>>>()
            var current = sections.getOrPut("") { mutableListOf() }
            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.isEmpty() || line.startsWith(";") || line.startsWith("#") -> Unit
                    line.startsWith("[") && line.endsWith("]") ->
                        current = sections.getOrPut(line.substring(1, line.length - 1)) { mutableListOf() }
                    "=" in line ->
                        current += line.substringBefore("=").trim() to line.substringAfter("=").trim()
                }
            }
            return Ini(sections)
        }
    }
}
