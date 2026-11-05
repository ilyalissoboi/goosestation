// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/** What a memory card in the editor's list is: a library game's own, a shared card, or another card file. */
enum class MemcardKind {
    Game,
    Shared,
    Other,
}

/**
 * A memory card the editor can open: its file's full [path], as the core opens it, the [name] it's shown by, its
 * [kind], and whether its file [exists] (a game's card doesn't until a save is written to it). Made by native code.
 */
class MemcardFile(
    val path: String,
    val name: String,
    val kind: MemcardKind,
    val exists: Boolean,
)
