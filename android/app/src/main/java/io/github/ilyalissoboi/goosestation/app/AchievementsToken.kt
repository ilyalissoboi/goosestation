// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals and opens a secret. Either gives null when it can't. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray?

    fun decrypt(sealed: ByteArray): ByteArray?
}

/**
 * [TokenCipher] with an AES-GCM key the Android Keystore holds, which never leaves the device (and isn't in any
 * backup). The sealed form is the 12-byte IV, then the ciphertext and its tag.
 */
class KeystoreCipher(
    private val alias: String = "retroachievements-token",
) : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray? =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.iv + cipher.doFinal(plain)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    override fun decrypt(sealed: ByteArray): ByteArray? =
        try {
            val key = key(create = false)
            if (key == null || sealed.size <= IV_BYTES) {
                null
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
                cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
            }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * The RetroAchievements login token, sealed by [cipher] in [store] (the app's private `achievements` preferences):
 * never in secrets.ini or the data folder. Every call blocks briefly: fine on any thread.
 */
class AchievementsToken(
    private val cipher: TokenCipher,
    private val store: KeyValueStore,
) {
    /** The token, or null for none. One that won't open (a restored backup, a new phone) is deleted: signed out. */
    fun load(): String? {
        val sealed = store.getString(KEY) ?: return null
        val plain = runCatching { Base64.getDecoder().decode(sealed) }.getOrNull()?.let(cipher::decrypt)
        if (plain == null) {
            store.putString(KEY, null)
            return null
        }
        return plain.decodeToString()
    }

    /** Keeps [token], or deletes the stored one with null or an empty one. */
    fun store(token: String?) {
        val sealed = token?.takeIf { it.isNotEmpty() }?.let { cipher.encrypt(it.encodeToByteArray()) }
        store.putString(KEY, sealed?.let { Base64.getEncoder().encodeToString(it) })
    }

    private companion object {
        const val KEY = "token"
    }
}
