// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

/** A document in a tree. content_bridge.cpp reads its fields by name, so they're kept in release builds. */
data class NativeDocument(
    val name: String,
    val documentId: String,
    val size: Long,
    val lastModifiedMs: Long,
    val isDirectory: Boolean,
)
