// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** The token's real cipher, on the device's Keystore. */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {
    private val cipher = KeystoreCipher()
    private val plain = "fake-token-1".encodeToByteArray()

    @Test
    fun roundTrip() {
        assertArrayEquals(plain, (cipher.decrypt(cipher.encrypt(plain)!!) as Opening.Opened).bytes)
    }

    @Test
    fun freshIvEachTime() {
        assertFalse(cipher.encrypt(plain)!!.contentEquals(cipher.encrypt(plain)!!))
    }

    @Test
    fun tamperedFails() {
        val sealed = cipher.encrypt(plain)!!
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertEquals(Opening.WontOpen, cipher.decrypt(sealed))
    }
}
