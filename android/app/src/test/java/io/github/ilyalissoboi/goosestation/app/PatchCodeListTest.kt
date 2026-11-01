// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.PatchCode
import org.junit.Assert.assertEquals
import org.junit.Test

/** The game's patch codes as the panel shows them: names and notes, in their groups. */
class PatchCodeListTest {
    private fun code(
        index: Int,
        group: String,
        name: String,
        manual: Boolean = false,
    ) = PatchCode(index, group, name, manual, false)

    @Test
    fun aColonSplitsTheNote() {
        assertEquals(
            "Infinite HP Slot 1" to "Turn Off When Out of Battle",
            PatchCodeList.split("Infinite HP Slot 1:Turn Off When Out of Battle"),
        )
    }

    @Test
    fun aNameWithoutAColonHasNoNote() {
        assertEquals("Max Money" to null, PatchCodeList.split("Max Money"))
    }

    @Test
    fun aLeadingColonKeepsTheName() {
        assertEquals(":Hidden" to null, PatchCodeList.split(":Hidden"))
        assertEquals("Trailing:" to null, PatchCodeList.split("Trailing:"))
    }

    @Test
    fun groupsKeepTheirOrderAndOtherComesLast() {
        val groups =
            PatchCodeList.groups(
                listOf(code(0, "Player", "A"), code(1, "", "B"), code(2, "Items", "C"), code(3, "Player", "D:Note")),
            )

        assertEquals(listOf("Player", "Items", "Other"), groups.map { it.title })
        assertEquals(listOf(0, 3), groups[0].codes.map { it.index })
        assertEquals("Note", groups[0].codes[1].note)
        assertEquals(listOf("B"), groups[2].codes.map { it.name })
    }

    @Test
    fun allUngroupedHasNoHeading() {
        val groups = PatchCodeList.groups(listOf(code(0, "", "A"), code(1, "", "B", manual = true)))

        assertEquals(1, groups.size)
        assertEquals(null, groups[0].title)
        assertEquals(listOf(false, true), groups[0].codes.map { it.manual })
    }
}
