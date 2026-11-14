// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

/** A [KeyValueStore] in memory, for tests: [values] is what it holds. */
class MemoryStore : KeyValueStore {
    val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(
        key: String,
        value: String?,
    ): Boolean {
        if (value == null) values.remove(key) else values[key] = value
        return true
    }
}
