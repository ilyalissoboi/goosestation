// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.content.Context
import android.util.Log
import java.util.UUID

/** The folders the user picked, as tree URI strings, and whether setup has ended. */
data class SavedFolders(
    val bios: String?,
    val games: List<String>,
    val data: String?,
    val setupComplete: Boolean,
) {
    /** Whether any slot holds [uri], so its grant is still needed. */
    fun uses(uri: String) = bios == uri || data == uri || uri in games
}

/** String preferences. [putString] with null removes the key, and returns false if the write failed. */
interface KeyValueStore {
    fun getString(key: String): String?

    fun putString(
        key: String,
        value: String?,
    ): Boolean
}

/** [KeyValueStore] on `SharedPreferences(name)`, written synchronously. A failed write is logged. */
class SharedPreferencesStore(
    context: Context,
    name: String = "folders",
) : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(
        key: String,
        value: String?,
    ): Boolean {
        val written = prefs.edit().putString(key, value).commit()
        if (!written) Log.e("GooseStation", "Can't save the preference $key.")
        return written
    }
}

/** The saved folders, the BIOS images last imported, and the install ID. */
class FolderPrefs(
    private val store: KeyValueStore,
) {
    fun load(): SavedFolders =
        SavedFolders(
            bios = store.getString(BIOS),
            // Content URIs are percent-encoded, so they never hold a line break.
            games =
                store
                    .getString(GAMES)
                    ?.lines()
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
            data = store.getString(DATA),
            setupComplete = store.getString(SETUP_COMPLETE) == "true",
        )

    /** Returns false if any write failed. Setup is marked complete last, so a partial save doesn't claim it. */
    fun save(folders: SavedFolders): Boolean {
        val foldersWritten =
            listOf(
                store.putString(BIOS, folders.bios),
                store.putString(GAMES, folders.games.joinToString("\n").ifEmpty { null }),
                store.putString(DATA, folders.data),
            ).all { it }
        return store.putString(SETUP_COMPLETE, folders.setupComplete.toString()) && foldersWritten
    }

    /** The BIOS images the last import found, as [saveBiosImages] saved them; empty if none was saved. */
    fun biosImages(): List<String> =
        store
            .getString(BIOS_IMAGES)
            ?.lines()
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** Returns false if the write failed. An image's description never holds a line break. */
    fun saveBiosImages(images: List<String>): Boolean =
        store.putString(BIOS_IMAGES, images.joinToString("\n").ifEmpty { null })

    /** Made on the first call, then kept, so the core can tell its own settings.ini from one copied from elsewhere. */
    fun installId(): String {
        store.getString(INSTALL_ID)?.let { return it }
        val id = UUID.randomUUID().toString()
        store.putString(INSTALL_ID, id)
        return id
    }

    private companion object {
        const val BIOS = "bios"
        const val GAMES = "games"
        const val DATA = "data"
        const val SETUP_COMPLETE = "setupComplete"
        const val INSTALL_ID = "installId"
        const val BIOS_IMAGES = "biosImages"
    }
}
