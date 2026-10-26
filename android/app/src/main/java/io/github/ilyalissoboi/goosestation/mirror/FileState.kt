// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

/** A file as a listing sees it. Two states that are equal mean the file is taken to be unchanged. */
data class FileState(
    val size: Long,
    val lastModifiedMs: Long,
)
