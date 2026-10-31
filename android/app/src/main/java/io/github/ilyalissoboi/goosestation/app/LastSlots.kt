// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.KeyValueStore

/** The slot last saved to or loaded from in each game, by serial. Both block: call them off the main thread. */
interface LastSlotStore {
    fun get(serial: String): Int?

    fun set(
        serial: String,
        slot: Int,
    )
}

/** [LastSlotStore] in [store], as `lastSlot.<serial>`. It stays on this device. */
class LastSlots(
    private val store: KeyValueStore,
) : LastSlotStore {
    override fun get(serial: String): Int? = store.getString(key(serial))?.toIntOrNull()

    override fun set(
        serial: String,
        slot: Int,
    ) {
        store.putString(key(serial), slot.toString())
    }

    private fun key(serial: String) = "lastSlot.$serial"
}
