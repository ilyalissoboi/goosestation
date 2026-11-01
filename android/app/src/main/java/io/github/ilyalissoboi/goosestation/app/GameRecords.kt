// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.KeyValueStore

/** What the app keeps per game, by its key, on this device. Every call blocks: make them off the main thread. */
interface GameRecordStore {
    /** The number of the disc last played in the game, or null if it has never been recorded. */
    fun lastDisc(key: String): Int?

    fun setLastDisc(
        key: String,
        disc: Int,
    )
}

/** [GameRecordStore] in [store], as `lastDisc.<key>`. */
class GameRecords(
    private val store: KeyValueStore,
) : GameRecordStore {
    override fun lastDisc(key: String): Int? = store.getString("lastDisc.$key")?.toIntOrNull()

    override fun setLastDisc(
        key: String,
        disc: Int,
    ) {
        store.putString("lastDisc.$key", disc.toString())
    }
}
