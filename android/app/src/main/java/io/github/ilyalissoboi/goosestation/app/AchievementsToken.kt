// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What opening a sealed secret gave. */
sealed interface Opening {
    class Opened(
        val bytes: ByteArray,
    ) : Opening

    /** It never will: its key is gone (a restored backup, a new phone), or it was changed. */
    data object WontOpen : Opening

    /** Not now: the Keystore failed. What's sealed is kept for the next try. */
    data object Failed : Opening
}

/** Seals and opens a secret. Sealing gives null when it can't. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray?

    fun decrypt(sealed: ByteArray): Opening
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
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "The Keystore can't seal a secret.", e)
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "The Keystore can't seal a secret.", e)
            null
        }

    override fun decrypt(sealed: ByteArray): Opening =
        try {
            val key = key(create = false)
            if (key == null || sealed.size <= IV_BYTES) {
                Opening.WontOpen
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
                Opening.Opened(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
            }
        } catch (_: AEADBadTagException) {
            Opening.WontOpen
        } catch (_: KeyPermanentlyInvalidatedException) {
            Opening.WontOpen
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "The Keystore can't open a secret for now.", e)
            Opening.Failed
        } catch (e: RuntimeException) {
            Log.w(TAG, "The Keystore can't open a secret for now.", e)
            Opening.Failed
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
        const val TAG = "GooseStationKeystore"
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
    /**
     * The token, or null for none. One that won't open (a restored backup, a new phone) is deleted: signed out. One the
     * Keystore can't open for now is kept, for the next try.
     */
    fun load(): String? {
        val sealed = store.getString(KEY) ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(sealed) }.getOrNull()
        return when (val opening = bytes?.let(cipher::decrypt) ?: Opening.WontOpen) {
            is Opening.Opened -> opening.bytes.decodeToString()
            Opening.WontOpen -> {
                store.putString(KEY, null)
                null
            }
            Opening.Failed -> null
        }
    }

    /** Keeps [token], or deletes the stored one with null or an empty one. One the Keystore can't seal leaves it. */
    fun store(token: String?) {
        if (token.isNullOrEmpty()) {
            store.putString(KEY, null)
            return
        }
        val sealed = cipher.encrypt(token.encodeToByteArray()) ?: return
        store.putString(KEY, Base64.getEncoder().encodeToString(sealed))
    }

    private companion object {
        const val KEY = "token"
    }
}
