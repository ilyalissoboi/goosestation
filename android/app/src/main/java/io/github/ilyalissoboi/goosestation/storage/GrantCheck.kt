// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.content.ContentResolver
import android.content.UriPermission

/** A persisted URI permission, as [ContentResolver.getPersistedUriPermissions] lists it. */
data class PersistedGrant(
    val uri: String,
    val read: Boolean,
    val write: Boolean,
)

enum class FolderKind { Bios, Games, Data }

/** A saved folder the app no longer holds the grant it needs for. */
data class MissingGrant(
    val kind: FolderKind,
    val uri: String,
)

fun UriPermission.toPersistedGrant() = PersistedGrant(uri.toString(), isReadPermission, isWritePermission)

/** This app's persisted grants. A binder call: keep it off the main thread. */
fun ContentResolver.persistedGrants(): List<PersistedGrant> = persistedUriPermissions.map { it.toPersistedGrant() }

object GrantCheck {
    /**
     * The saved folders without the grant they need, in BIOS, games, data order. Data needs read and write; BIOS and
     * games need read. A grant counts only for the URI string it was taken on: not one differing in case or by a
     * trailing slash, and not a parent tree's.
     */
    fun missing(
        saved: SavedFolders,
        persisted: List<PersistedGrant>,
    ): List<MissingGrant> {
        val byUri = persisted.associateBy { it.uri }

        fun readable(uri: String) = byUri[uri]?.read == true

        fun writable(uri: String) = byUri[uri]?.let { it.read && it.write } == true

        return buildList {
            saved.bios?.let { if (!readable(it)) add(MissingGrant(FolderKind.Bios, it)) }
            saved.games.forEach { if (!readable(it)) add(MissingGrant(FolderKind.Games, it)) }
            saved.data?.let { if (!writable(it)) add(MissingGrant(FolderKind.Data, it)) }
        }
    }
}
