// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.SaveStatePreview

/**
 * Where a save state is: [key]'s [slot] (a game's key, or the serial another disc's state is still filed under;
 * [SaveSlots.RESUME] for the resume state), or, [shared], desktop's shared slot [slot] ([key] unused).
 */
data class StateRef(
    val key: String,
    val slot: Int,
    val shared: Boolean = false,
)

/** The slot lists' tabs: the game's own slots, and desktop's shared (global) ones. */
enum class SlotTab { ThisGame, Shared }

/**
 * The game a shared slot holds: its [title] (the library's, when [path] names a game the app knows, else the one the
 * state recorded), its [serial], and its [path] (null when it's no game the app knows).
 */
data class HeldGame(
    val title: String,
    val serial: String,
    val path: String?,
) {
    /** As a row names it: the library's title, or, for a game the library doesn't know, its serial. */
    val label: String get() = if (path != null) title else serial.ifBlank { title }
}

/** The state a row stands for, in a game whose key is [key]. */
fun SlotEntry.ref(key: String): StateRef = StateRef(if (shared) "" else owner ?: key, slot, shared)

/** The rows of the slot lists, as the in-game slot panel and the library's Saves page show them. */
object SaveLists {
    /** Save lists slots 1 to 10; Load lists the saved ones, the resume state first, when there is one. */
    fun entries(
        mode: SlotMode,
        times: LongArray,
    ): List<SlotEntry> {
        val slots = (1..SaveSlots.COUNT).map { SlotEntry(it, times.getOrNull(it)?.takeIf { time -> time > 0 }) }
        if (mode == SlotMode.Save) return slots
        // Load lists what can be loaded: the saved slots, the resume state first.
        val resume = times.getOrNull(0)?.takeIf { it > 0 }?.let { SlotEntry(SaveSlots.RESUME, it) }
        return listOfNotNull(resume) + slots.filter { it.savedAt != null }
    }

    /** The shared slots from their [times] (`[n]` slot n): Save lists all ten, Load the saved ones. */
    fun shared(
        mode: SlotMode,
        times: LongArray,
    ): List<SlotEntry> {
        val slots =
            (1..SaveSlots.COUNT).map {
                SlotEntry(it, times.getOrNull(it)?.takeIf { time -> time > 0 }, shared = true)
            }
        return if (mode == SlotMode.Save) slots else slots.filter { it.savedAt != null }
    }

    /**
     * The slot to focus: the [last] one used, if Save lists it or Load has a save there; else Slot 1 (Save) or the
     * newest save (Load), if any.
     */
    fun focus(
        mode: SlotMode,
        entries: List<SlotEntry>,
        last: Int?,
    ): Int? =
        when (mode) {
            SlotMode.Save -> last?.takeIf { it in 1..SaveSlots.COUNT } ?: 1
            SlotMode.Load ->
                last?.takeIf { slot -> entries.any { it.slot == slot && it.savedAt != null } }
                    ?: entries.filter { it.savedAt != null }.maxByOrNull { it.savedAt!! }?.slot
        }

    /** The game a state's [preview] says it holds, or null when it says nothing of one. */
    fun held(preview: SaveStatePreview): HeldGame? {
        if (preview.serial.isBlank() && preview.title.isBlank() && preview.gamePath.isBlank()) return null
        val path = preview.gamePath.ifBlank { null }
        val title = if (path != null) preview.gameTitle.ifBlank { preview.title } else preview.title
        return HeldGame(title, preview.serial, path)
    }

    /** The save, as a message names it: "slot 3", "shared slot 3" or "your resume state". */
    fun where(entry: SlotEntry): String =
        when {
            entry.shared -> "shared slot ${entry.slot}"
            entry.slot == SaveSlots.RESUME -> "your resume state"
            else -> "slot ${entry.slot}"
        }

    /** What the question before loading shared [entry], another game's, asks: that game starts in this one's place. */
    fun switchQuestion(entry: SlotEntry): String =
        "Load ${entry.holds?.label.orEmpty()} from shared slot ${entry.slot}? This game stops."

    /** Why shared [entry]'s game can't start: the library doesn't have it. */
    fun notInLibrary(entry: SlotEntry): String {
        val holds = entry.holds
        val game =
            if (holds == null ||
                holds.serial.isBlank()
            ) {
                holds?.title.orEmpty()
            } else {
                "${holds.title} (${holds.serial})"
            }
        return "Shared slot ${entry.slot} needs $game, which isn't in your library."
    }

    /** What the question before deleting [entry] asks. */
    fun deleteQuestion(entry: SlotEntry): String {
        val resume = entry.slot == SaveSlots.RESUME
        return when {
            entry.shared -> "Delete the save in shared slot ${entry.slot}?"
            entry.owner != null -> {
                val disc = entry.disc?.let { "disc $it's" } ?: "the other disc's"
                if (resume) "Delete $disc resume state?" else "Delete $disc save in slot ${entry.slot}?"
            }
            resume -> "Delete your resume state?"
            else -> "Delete the save in slot ${entry.slot}?"
        }
    }
}
