// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSecretsTest {
    private val store = MemoryStore()
    private val values = store.values

    // Stands in for the Keystore: reversible, but not the plain bytes. [broken]: what's sealed won't open (its key is
    // gone); [failing]: the Keystore fails for now.
    private var broken = false
    private var failing = false
    private var decrypts = 0
    private val cipher =
        object : TokenCipher {
            override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()

            override fun decrypt(sealed: ByteArray): Opening {
                decrypts++
                return when {
                    failing -> Opening.Failed
                    broken -> Opening.WontOpen
                    else -> Opening.Opened(sealed.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
                }
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

    // From review: a Keystore that fails for a moment isn't a key that's gone; a passphrase lost is lost for good.
    @Test
    fun aKeystoreFailureKeepsTheSecret() {
        assertTrue(secrets.store(SyncSecrets.ENCRYPTION_PASSPHRASE, "not-a-real-passphrase"))
        failing = true
        assertNull(secrets.load(SyncSecrets.ENCRYPTION_PASSPHRASE))
        assertTrue(secrets.has(SyncSecrets.ENCRYPTION_PASSPHRASE))
        failing = false
        assertEquals("not-a-real-passphrase", secrets.load(SyncSecrets.ENCRYPTION_PASSPHRASE))
    }
}
