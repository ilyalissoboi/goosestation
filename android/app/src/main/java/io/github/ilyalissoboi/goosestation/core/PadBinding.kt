// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/** What a Port 1 input takes: a button, or one side of an axis (a stick direction or a trigger). */
enum class PadBindingKind { Button, Axis, HalfAxis }

/**
 * One of Port 1's inputs, as one controller has it bound: the core's [name] for it (the `[Pad1]` key, such as
 * `Cross`), its [displayName] (`D-Pad Up`), its [kind], and the controller's [binding], the part after `Android-<n>/`
 * (such as `Button96` or `+Axis23`), or null if it has none. Made by native code.
 */
data class PadBinding(
    val name: String,
    val displayName: String,
    val kind: PadBindingKind,
    val binding: String?,
)
