// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AchievementsTokenTest {
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

    // Stands in for the Keystore: reversible, but not the plain bytes. [broken]: what's sealed won't open (its key is
    // gone); [failing]: the Keystore fails for now, to open or to seal.
    private var broken = false
    private var failing = false
    private val cipher =
        object : TokenCipher {
            override fun encrypt(plain: ByteArray) =
                if (failing) null else plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()

            override fun decrypt(sealed: ByteArray) =
                when {
                    failing -> Opening.Failed
                    broken -> Opening.WontOpen
                    else -> Opening.Opened(sealed.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
                }
        }

    private val token = AchievementsToken(cipher, store)

    @Test
    fun storeThenLoad() {
        token.store("fake-token-1")
        assertEquals("fake-token-1", token.load())
    }

    @Test
    fun nullDeletes() {
        token.store("fake-token-1")
        token.store(null)
        assertNull(token.load())
        assertEquals(emptyMap<String, String>(), values)
    }

    // A restored backup or a new phone has the ciphertext without the key that made it: signed out, not stuck.
    @Test
    fun undecryptableIsDeletedAndSignedOut() {
        token.store("fake-token-1")
        broken = true
        assertNull(token.load())
        assertEquals(emptyMap<String, String>(), values)
    }

    // From review: a Keystore that fails for a moment isn't a key that's gone; the token stays for the next try.
    @Test
    fun aKeystoreFailureKeepsTheToken() {
        token.store("fake-token-1")
        failing = true
        assertNull(token.load())
        failing = false
        assertEquals("fake-token-1", token.load())
    }

    // A new token the Keystore can't seal leaves the stored one, rather than deleting it.
    @Test
    fun aTokenThatCantBeSealedKeepsTheOneStored() {
        token.store("fake-token-1")
        failing = true
        token.store("fake-token-2")
        failing = false
        assertEquals("fake-token-1", token.load())
    }

    @Test
    fun storedValueIsNotThePlainToken() {
        token.store("fake-token-1")
        assertFalse(values.values.any { "fake-token-1" in it })
    }
}
