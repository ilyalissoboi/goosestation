// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.ArgbImage

/** A message over the game: its [text], the [key] a later one replaces it by, its [icon], and when it goes. */
data class OsdMessage(
    val id: Long,
    val key: String?,
    val text: String,
    val icon: ArgbImage?,
    val untilMs: Long,
)

/**
 * The messages shown over the game, oldest first, at most [MAX_SHOWN]. Each lasts its seconds by [clock] (in ms), and
 * goes once [expire] is called after that. Main thread only.
 */
class OsdMessages(
    private val clock: () -> Long,
) {
    var shown by mutableStateOf(emptyList<OsdMessage>())
        private set

    private var nextId = 0L

    /**
     * Shows [text] for [seconds]. A message with the same [key] already shown is replaced in place; any other goes last,
     * and the oldest goes if there are too many.
     */
    fun post(
        text: String,
        key: String? = null,
        seconds: Float = APP_SECONDS,
        icon: ArgbImage? = null,
    ) {
        val message = OsdMessage(nextId++, key, text, icon, clock() + (seconds * 1000).toLong())
        val index = if (key == null) -1 else shown.indexOfFirst { it.key == key }
        shown =
            if (index >= 0) {
                shown.toMutableList().also { it[index] = message }
            } else {
                (shown + message).takeLast(MAX_SHOWN)
            }
    }

    /** Takes [key]'s message away. */
    fun remove(key: String) {
        shown = shown.filterNot { it.key == key }
    }

    /** Takes away the messages whose time is up. */
    fun expire() {
        val now = clock()
        if (shown.any { it.untilMs <= now }) shown = shown.filter { it.untilMs > now }
    }

    /** When the next message's time is up, or null with none shown. */
    fun nextExpiry(): Long? = shown.minOfOrNull { it.untilMs }

    fun clear() {
        shown = emptyList()
    }

    companion object {
        /** How long the app's own messages last. */
        const val APP_SECONDS = 3f
        const val MAX_SHOWN = 4
    }
}
