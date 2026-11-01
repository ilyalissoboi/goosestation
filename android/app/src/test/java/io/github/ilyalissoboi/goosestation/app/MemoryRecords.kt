// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/** A [GameRecordStore] in memory, for the tests. */
class MemoryRecords : GameRecordStore {
    val lastDiscs = HashMap<String, Int>()

    override fun lastDisc(key: String): Int? = lastDiscs[key]

    override fun setLastDisc(
        key: String,
        disc: Int,
    ) {
        lastDiscs[key] = disc
    }
}
