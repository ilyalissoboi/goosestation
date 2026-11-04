// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import java.util.Base64

/**
 * Memory card sync's secrets, the storage's secret access key and the encryption passphrase, sealed by [cipher] in
 * [store] (the app's private `memcard_sync` preferences): never in settings.ini or the data folder. They're named as
 * the core names them ([SECRET_ACCESS_KEY], [ENCRYPTION_PASSPHRASE]); any other name is refused. Every call blocks
 * briefly: fine on any thread.
 */
class SyncSecrets(
    private val cipher: TokenCipher,
    private val store: KeyValueStore,
) {
    /** [name]'s secret, or null for none. One that won't open (a restored backup, a new phone) is deleted. */
    fun load(name: String): String? {
        if (name !in NAMES) return null
        val sealed = store.getString(name) ?: return null
        val plain = runCatching { Base64.getDecoder().decode(sealed) }.getOrNull()?.let(cipher::decrypt)
        if (plain == null) {
            store.putString(name, null)
            return null
        }
        return plain.decodeToString()
    }

    /** Keeps [value] as [name]'s secret, or deletes it with null or an empty one. False if it couldn't. */
    fun store(
        name: String,
        value: String?,
    ): Boolean {
        if (name !in NAMES) return false
        if (value.isNullOrEmpty()) return store.putString(name, null)
        val sealed = cipher.encrypt(value.encodeToByteArray()) ?: return false
        return store.putString(name, Base64.getEncoder().encodeToString(sealed))
    }

    /** Whether [name] has a secret stored, without opening it. */
    fun has(name: String): Boolean = name in NAMES && store.getString(name) != null

    companion object {
        const val SECRET_ACCESS_KEY = "s3-secret-access-key"
        const val ENCRYPTION_PASSPHRASE = "encryption-passphrase"
        private val NAMES = setOf(SECRET_ACCESS_KEY, ENCRYPTION_PASSPHRASE)
    }
}
