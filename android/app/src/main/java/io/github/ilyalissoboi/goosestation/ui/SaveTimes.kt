// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** When a save state was saved, as the slot list and the resume prompt say it. */
object SaveTimes {
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /**
     * [savedAt] (Unix seconds), seen from [now] in its zone: "Today · 14:05", "Yesterday · 09:30", else
     * "28 Oct 2026 · 21:17".
     */
    fun label(
        savedAt: Long,
        now: ZonedDateTime,
    ): String {
        val saved = Instant.ofEpochSecond(savedAt).atZone(now.zone)
        val day =
            when (saved.toLocalDate()) {
                now.toLocalDate() -> "Today"
                now.toLocalDate().minusDays(1) -> "Yesterday"
                else -> date.format(saved)
            }
        return "$day · ${time.format(saved)}"
    }
}
