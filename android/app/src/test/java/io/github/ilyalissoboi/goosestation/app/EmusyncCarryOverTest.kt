// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EmusyncCarryOverTest {
    @get:Rule val temp = TemporaryFolder()

    private val values = mutableMapOf<String, String>()
    private val store =
        object : KeyValueStore {
            override fun getString(key: String) = values[key]

            override fun putString(
                key: String,
                value: String?,
            ): Boolean {
                if (value == null) values.remove(key) else values[key] = value
                return true
            }
        }

    private fun workingCopy() =
        temp.newFolder("data").also {
            File(it, "emusync/ab12cd34ef56ab78").mkdirs()
            File(it, "emusync/ab12cd34ef56ab78/device.json").writeText("the desktop's identity")
            File(it, "memcards").mkdirs()
            File(it, "memcards/shared_card_1.mcd").writeText("a card")
        }

    // An earlier build mirrored emusync/ in from the data folder: it's the desktop's, never this device's own.
    @Test
    fun anEarlierBuildsCopyIsDroppedOnce() {
        val data = workingCopy()

        EmusyncCarryOver.drop(data, store)

        assertFalse(File(data, "emusync").exists())
        assertTrue(File(data, "memcards/shared_card_1.mcd").exists())
    }

    // From then on emusync/ is this device's own sync data.
    @Test
    fun thisDevicesOwnIsKept() {
        val data = workingCopy()
        EmusyncCarryOver.drop(data, store)
        File(data, "emusync/1234/device.json").apply { parentFile!!.mkdirs() }.writeText("this device's identity")

        EmusyncCarryOver.drop(data, store)

        assertTrue(File(data, "emusync/1234/device.json").exists())
    }

    @Test
    fun aFreshInstallHasNothingToDrop() {
        val data = temp.newFolder("fresh")

        EmusyncCarryOver.drop(data, store)
        File(data, "emusync/1234").mkdirs()
        EmusyncCarryOver.drop(data, store)

        assertTrue(File(data, "emusync/1234").exists())
    }
}
