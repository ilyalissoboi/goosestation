// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GamePatch
import io.github.ilyalissoboi.goosestation.core.PatchCode

/**
 * A code's values, as a row offers them: its named [options] (name and value), or a [range], and the [value] in it now.
 */
data class CodeValues(
    val options: List<Pair<String, Int>>,
    val range: IntRange?,
    val value: Int,
)

/**
 * A patch as the panel shows it: its [index] in the game's patch list, [group] (null for none), [title], its [choices]'
 * names (one for a plain patch), the choice its switches say ([saved]) and the one running ([running]; -1 is off), the
 * shown choice's [values] (null without), a [note], and whether it can be switched on ([canEnable]).
 */
data class PatchRow(
    val index: Int,
    val group: String?,
    val title: String,
    val choices: List<String>,
    val saved: Int,
    val running: Int,
    val values: CodeValues?,
    val note: String?,
    val canEnable: Boolean,
)

/**
 * A cheat as the panel shows it: its [index] in the game's list, its [name] and [note] (the part of its name after
 * a colon, if any), whether it's applied by hand ([manual]: an Apply row), whether it's [on], and its [values] (null
 * without).
 */
data class PatchCodeRow(
    val index: Int,
    val name: String,
    val note: String?,
    val manual: Boolean,
    val on: Boolean,
    val values: CodeValues? = null,
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
                code.group to
                    PatchCodeRow(
                        code.index,
                        name,
                        note,
                        code.manual,
                        code.on,
                        values(code.optionNames, code.optionValues, code.rangeMin, code.rangeMax, code.value),
                    )
            }
        val named = rows.filter { it.first.isNotBlank() }.groupBy({ it.first }, { it.second })
        val ungrouped = rows.filter { it.first.isBlank() }.map { it.second }
        if (named.isEmpty()) return if (ungrouped.isEmpty()) emptyList() else listOf(PatchCodeGroup(null, ungrouped))
        return named.map { (title, list) -> PatchCodeGroup(title, list) } +
            listOfNotNull(ungrouped.takeIf { it.isNotEmpty() }?.let { PatchCodeGroup("Other", it) })
    }

    /** A code's values, or null without: named options, else a range (none when [max] is below [min]). */
    fun values(
        names: Array<String>,
        values: IntArray,
        min: Int,
        max: Int,
        value: Int,
    ): CodeValues? =
        when {
            names.isNotEmpty() -> CodeValues(names.zip(values.toList()), null, value)
            max >= min -> CodeValues(emptyList(), min..max, value)
            else -> null
        }

    /** The game's patches as rows. */
    fun patches(patches: List<GamePatch>): List<PatchRow> =
        patches.map { patch ->
            PatchRow(
                patch.index,
                patch.group.ifBlank { null },
                patch.title,
                patch.choices.toList(),
                patch.saved,
                patch.running,
                values(patch.optionNames, patch.optionValues, patch.rangeMin, patch.rangeMax, patch.value),
                patch.note.ifBlank { null },
                patch.canEnable,
            )
        }

    /** What a patch waits for the next start to do, if anything: "Off at the next start", or its next choice. */
    fun pending(row: PatchRow): String? =
        when {
            row.running == row.saved -> null
            row.saved < 0 -> "Off at the next start"
            row.choices.size == 1 -> "On at the next start"
            else -> "${row.choices[row.saved]} at the next start"
        }
}
