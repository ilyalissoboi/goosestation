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
    /** What to show for the core's message [text] with [key] (empty for none), or null to show nothing. */
    fun translate(
        key: String,
        text: String,
    ): String? =
        when {
            key == "save_state" || key == "load_state" -> null
            key == "system_reset" -> "Console reset"
            key == "analog_mode_toggle_0" || key == "Controller0AnalogMode" -> analogMode(text)
            key.startsWith("ScreenshotSaver_") ->
                when {
                    text.startsWith("Saved") -> "Screenshot saved"
                    text.startsWith("Failed") -> "Couldn't save the screenshot"
                    else -> null
                }
            else -> text
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
