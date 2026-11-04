// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.SyncConflict
import io.github.ilyalissoboi.goosestation.core.SyncVersion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the conflict screen says of memory card sync's conflicts, as Big Picture says it. Times in 24-hour form. */
object SyncConflictWords {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** The line over the list, for [count] conflicts. */
    fun intro(count: Int): String =
        if (count == 1) {
            "1 save changed both on this device and on another. Choose which version to keep."
        } else {
            "$count saves changed both on this device and on another. Choose which version to keep."
        }

    /** One version: its device, when, and its blocks, or that it deleted the save. "?" for a time that can't be real. */
    fun version(
        v: SyncVersion,
        zone: ZoneId,
    ): String {
        val time = if (v.timeKnown) TIME.format(Instant.ofEpochMilli(v.modifiedMs).atZone(zone)) else "?"
        return when {
            v.deleted -> "${v.device}, deleted $time"
            v.blocks == 1 -> "${v.device}, $time, 1 block"
            else -> "${v.device}, $time, ${v.blocks} blocks"
        }
    }

    /** A conflict's line under its name: this device's version, then the others'. */
    fun summary(
        c: SyncConflict,
        zone: ZoneId,
    ): String {
        val here = c.local?.let { version(it, zone) } ?: "not on this device"
        return "This device: $here; other: ${c.remote.joinToString(", ") { version(it, zone) }}"
    }

    /** A conflict's choices, in [SyncConflict]'s order: this device's version, each other one, then deciding later. */
    fun choices(c: SyncConflict): List<String> =
        listOf("This device's") + c.remote.map { "${it.device}'s" } + "Decide later"
}
