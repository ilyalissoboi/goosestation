// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryCardSettingsTest {
    @Test
    fun theDefaultCardComesFirstAndOnlyOnce() {
        assertEquals(
            listOf("shared_card_1.mcd (the default)", "a.mcd"),
            MemoryCardSettings.sharedCards(1, listOf("a.mcd", "shared_card_1.mcd"), "").map { it.label.unbroken() },
        )
        assertEquals(
            listOf("", "a.mcd", "shared_card_1.mcd"),
            MemoryCardSettings.sharedCards(2, listOf("a.mcd", "shared_card_1.mcd"), "").map { it.value },
        )
    }

    @Test
    fun aCardSettingsIniNamesIsKept() {
        assertEquals(
            "gone.mcd (from settings.ini)",
            MemoryCardSettings
                .sharedCards(1, listOf("a.mcd"), "gone.mcd")
                .last()
                .label
                .unbroken(),
        )
    }

    @Test
    fun aLongFileNameCanWrapAtItsPunctuation() {
        val label = MemoryCardSettings.sharedCards(1, listOf("a_long-name.mcd"), "").last().label

        assertEquals("a_\u200Blong-\u200Bname.\u200Bmcd", label)
        assertEquals("a_long-name.mcd", label.replace("\u200B", ""))
    }

    @Test
    fun eachSlotHasItsKeys() {
        assertEquals("Card1Type", MemoryCardSettings.type(1).key)
        assertEquals("Card2Path", MemoryCardSettings.path(2).key)
        val values = SettingValues.read(MemoryCoreSettings(), MemoryCardSettings.all)
        assertEquals("PerGameTitle", values[MemoryCardSettings.type(1)])
        assertEquals("None", values[MemoryCardSettings.type(2)])
        assertEquals("", values[MemoryCardSettings.path(1)])
        assertEquals(true, values[MemoryCardSettings.ONE_CARD_PER_GAME])
    }

    private fun String.unbroken() = replace("\u200B", "")
}
