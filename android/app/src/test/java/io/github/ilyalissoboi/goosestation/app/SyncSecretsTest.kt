// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSecretsTest {
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

    // Stands in for the Keystore: reversible, but not the plain bytes. [broken] makes decrypting fail.
    private var broken = false
    private var decrypts = 0
    private val cipher =
        object : TokenCipher {
            override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()

            override fun decrypt(sealed: ByteArray): ByteArray? {
                decrypts++
                return if (broken) null else sealed.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
            }
        }

    private val secrets = SyncSecrets(cipher, store)

    @Test
    fun storedValueLoadsBack() {
        assertTrue(secrets.store(SyncSecrets.SECRET_ACCESS_KEY, "not-a-real-secret"))
        assertTrue(secrets.store(SyncSecrets.ENCRYPTION_PASSPHRASE, "not-a-real-passphrase"))

        assertEquals("not-a-real-secret", secrets.load(SyncSecrets.SECRET_ACCESS_KEY))
        assertEquals("not-a-real-passphrase", secrets.load(SyncSecrets.ENCRYPTION_PASSPHRASE))
        assertFalse(values.values.any { "not-a-real" in it })
    }

    @Test
    fun emptyOrNullDeletes() {
        secrets.store(SyncSecrets.SECRET_ACCESS_KEY, "not-a-real-secret")
        secrets.store(SyncSecrets.ENCRYPTION_PASSPHRASE, "not-a-real-passphrase")

        assertTrue(secrets.store(SyncSecrets.SECRET_ACCESS_KEY, null))
        assertTrue(secrets.store(SyncSecrets.ENCRYPTION_PASSPHRASE, ""))

        assertNull(secrets.load(SyncSecrets.SECRET_ACCESS_KEY))
        assertFalse(secrets.has(SyncSecrets.ENCRYPTION_PASSPHRASE))
        assertEquals(emptyMap<String, String>(), values)
    }

    @Test
    fun hasDoesNotDecrypt() {
        secrets.store(SyncSecrets.SECRET_ACCESS_KEY, "not-a-real-secret")

        assertTrue(secrets.has(SyncSecrets.SECRET_ACCESS_KEY))
        assertFalse(secrets.has(SyncSecrets.ENCRYPTION_PASSPHRASE))
        assertEquals(0, decrypts)
    }

    @Test
    fun unknownNameIsRefused() {
        assertFalse(secrets.store("retroachievements-token", "fake-token-1"))
        assertNull(secrets.load("retroachievements-token"))
        assertFalse(secrets.has("retroachievements-token"))
        assertEquals(emptyMap<String, String>(), values)
    }

    // A restored backup or a new phone has the ciphertext without the key that made it: no secret, not stuck.
    @Test
    fun valueThatWontOpenIsDeleted() {
        secrets.store(SyncSecrets.SECRET_ACCESS_KEY, "not-a-real-secret")
        broken = true

        assertNull(secrets.load(SyncSecrets.SECRET_ACCESS_KEY))
        assertFalse(secrets.has(SyncSecrets.SECRET_ACCESS_KEY))
        assertEquals(emptyMap<String, String>(), values)
    }
}
