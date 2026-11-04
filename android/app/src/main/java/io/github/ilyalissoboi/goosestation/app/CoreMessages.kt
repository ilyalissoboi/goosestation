// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/**
 * The core's on-screen messages, as the app shows them. The menu's own actions are said in the app's words: a save or
 * load the app reports itself, so the core's goes; a reset, the analog mode and a screenshot get the app's wording.
 * Every other message shows as the core wrote it. Android has no translations, so the core's texts are its English
 * source strings.
 */
object CoreMessages {
    // Emoji and other pictographs (the core puts one before many messages), with their variation selectors and joiners:
    // the interface shows none.
    private val symbols = Regex("""[\p{So}\uFE0E\uFE0F\u200D]""")
    private val spaces = Regex("\\s+")

    /**
     * What to show for the core's message [message] with [key] (empty for none), or null to show nothing. Its emoji are
     * left out.
     */
    fun translate(
        key: String,
        message: String,
    ): String? {
        val text = message.replace(symbols, "").replace(spaces, " ").trim()
        return when {
            key == "save_state" || key == "load_state" -> null
            // The app says which disc went in.
            key == "DiscInserted" || key == "media_switch_subimage" || key == "ReloadMemoryCardsFromGameChange" -> null
            key == "system_reset" -> "Console reset"
            key == "CheatsLoadWarning" -> codesOn(text)
            key == "analog_mode_toggle_0" || key == "Controller0AnalogMode" -> analogMode(text)
            // Memory card sync: a conflict found while playing waits for the game to end, as settings do.
            key.startsWith(
                "MemcardSync-Conflict-",
            ) -> "A save needs your choice. Settle it in Memory cards after this game."
            key.startsWith("MemcardSync-") && text.startsWith("Updated ") -> updatedSaves(text)
            key.startsWith("ScreenshotSaver_") ->
                when {
                    text.startsWith("Saved") -> "Screenshot saved"
                    text.startsWith("Failed") -> "Couldn't save the screenshot"
                    else -> null
                }
            else -> text
        }
    }

    // "Updated 2 save(s) from other devices on shared_card_1.mcd": the count is the core's, the card its file.
    private fun updatedSaves(text: String): String {
        val count = text.removePrefix("Updated ").takeWhile { it.isDigit() }.toIntOrNull() ?: return text
        return if (count == 1) "Updated 1 save from other devices" else "Updated $count saves from other devices"
    }

    // "2 cheat(s) are enabled. This may crash games." The count is the core's.
    private fun codesOn(text: String): String {
        val count = text.takeWhile { it.isDigit() }.toIntOrNull() ?: return text
        return if (count == 1) "1 patch code is on" else "$count patch codes are on"
    }

    private fun analogMode(text: String): String {
        val analog = "analog mode" in text
        return when {
            "locked" in text -> "The game keeps the controller in ${if (analog) "analog" else "digital"} mode."
            analog -> "Analog mode on"
            else -> "Analog mode off"
        }
    }
}
