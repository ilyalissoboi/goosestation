// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.Choice

/** The Memory cards page's card slot settings, by settings.ini's names, each with the core's default. */
object MemoryCardSettings {
    private val CARD1_TYPE = NameSetting("MemoryCards", "Card1Type", "PerGameTitle", NameList.MemoryCardType)
    private val CARD2_TYPE = NameSetting("MemoryCards", "Card2Type", "None", NameList.MemoryCardType)

    // Empty for the slot's default shared card, shared_card_<slot>.mcd.
    private val CARD1_PATH = StringSetting("MemoryCards", "Card1Path", "")
    private val CARD2_PATH = StringSetting("MemoryCards", "Card2Path", "")

    /** One card for every disc of a game, named after it rather than after each disc. */
    val ONE_CARD_PER_GAME = BoolSetting("MemoryCards", "UsePlaylistTitle", true)

    val all: List<Setting<*>> = listOf(CARD1_TYPE, CARD1_PATH, CARD2_TYPE, CARD2_PATH, ONE_CARD_PER_GAME)

    /** A game's own: the card types and shared cards, as desktop's game pages have them (one card stays global). */
    val GAME: List<Setting<*>> = listOf(CARD1_TYPE, CARD1_PATH, CARD2_TYPE, CARD2_PATH)

    /** The core's name for a card the slot's games share. */
    const val SHARED = "Shared"

    /** Slot [slot]'s (1 or 2) card type. */
    fun type(slot: Int) = if (slot == 1) CARD1_TYPE else CARD2_TYPE

    /** Slot [slot]'s shared card, when its type is [SHARED]. */
    fun path(slot: Int) = if (slot == 1) CARD1_PATH else CARD2_PATH

    /**
     * The shared cards slot [slot] can use: its default first (the empty value), then the memory card folder's [cards]
     * but the default's own file, then a [current] card settings.ini names that isn't among them.
     */
    fun sharedCards(
        slot: Int,
        cards: List<String>,
        current: String,
    ): List<Choice<String>> {
        val default = "shared_card_$slot.mcd"
        return offered(
            listOf(Choice("", "${breakable(default)} (the default)")) +
                cards.filter { it != default }.map { Choice(it, breakable(it)) },
            current,
        ) { breakable(it) }
    }

    /**
     * A file name as a row shows it: able to wrap after each `_`, `-` and `.`, as a long one from desktop may have to
     * on a narrow screen, where Android would otherwise break it between letters.
     */
    private fun breakable(name: String) = name.replace(Regex("([_.-])"), "$1\u200B")
}
