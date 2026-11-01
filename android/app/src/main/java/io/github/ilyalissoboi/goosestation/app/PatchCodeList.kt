// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.PatchCode

/**
 * A patch code as the panel shows it: its [index] in the game's list, its [name] and [note] (the part of its name after
 * a colon, if any), whether it's applied by hand ([manual]: an Apply row) and whether it's [on].
 */
data class PatchCodeRow(
    val index: Int,
    val name: String,
    val note: String?,
    val manual: Boolean,
    val on: Boolean,
)

/** A group of codes, under its [title], or under none when no code of the game has a group. */
data class PatchCodeGroup(
    val title: String?,
    val codes: List<PatchCodeRow>,
)

/** The game's patch codes, put as the panel shows them. */
object PatchCodeList {
    /**
     * A name and its note: the database writes notes into names after a colon ("Infinite HP Slot 1:Turn Off When Out of
     * Battle"). A name with nothing on one side of its colon stays whole, with no note.
     */
    fun split(name: String): Pair<String, String?> {
        val colon = name.indexOf(':')
        if (colon < 0) return name to null
        val head = name.substring(0, colon).trim()
        val note = name.substring(colon + 1).trim()
        return if (head.isEmpty() || note.isEmpty()) name to null else head to note
    }

    /**
     * [codes] in their groups, in the order the groups first appear. Codes without a group come last, under "Other", or
     * under no heading when no code has a group.
     */
    fun groups(codes: List<PatchCode>): List<PatchCodeGroup> {
        val rows =
            codes.map { code ->
                val (name, note) = split(code.name)
                code.group to PatchCodeRow(code.index, name, note, code.manual, code.on)
            }
        val named = rows.filter { it.first.isNotBlank() }.groupBy({ it.first }, { it.second })
        val ungrouped = rows.filter { it.first.isBlank() }.map { it.second }
        if (named.isEmpty()) return if (ungrouped.isEmpty()) emptyList() else listOf(PatchCodeGroup(null, ungrouped))
        return named.map { (title, list) -> PatchCodeGroup(title, list) } +
            listOfNotNull(ungrouped.takeIf { it.isNotEmpty() }?.let { PatchCodeGroup("Other", it) })
    }
}
