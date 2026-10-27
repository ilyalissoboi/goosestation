// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

/** A game's total play time, as the library shows it. */
object PlayTime {
    fun format(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        return when {
            seconds <= 0 -> "Never played"
            seconds < 60 -> "Less than a minute"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
}
