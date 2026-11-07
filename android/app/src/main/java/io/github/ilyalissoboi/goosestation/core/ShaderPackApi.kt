// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/** One of the core's shader packs (its ShaderPacks table) and what this install has of it. */
data class ShaderPackInfo(
    val id: String,
    val name: String,
    val description: String,
    val licence: String,
    /** The id of a pack it needs installed first, or null. */
    val needs: String?,
    /** The installed version, or null when it isn't installed. */
    val installedVersion: String?,
    /** Installed part-way: an install that stopped. */
    val incomplete: Boolean,
    /** The installed archive's size, worded ("48 KB"); empty when unknown or not installed. */
    val installedSize: String,
)

/** A pack's latest version from GitHub: its [version] and worded [size], or the [error] that stopped the check. */
data class ShaderPackCheck(
    val id: String,
    val version: String?,
    val size: String,
    val error: String,
)

/** How an install or a removal went for one pack. */
data class ShaderPackResult(
    val id: String,
    val ok: Boolean,
    val cancelled: Boolean,
    val message: String,
)

/** A pack's state ("NotInstalled", "Installed", "UpdateAvailable", "Incomplete") and the core's words for it. */
data class ShaderPackStatus(
    val state: String,
    val words: String,
)

/** What a check or an install reports as it goes, on the thread it runs on; [cancelled] stops it. */
interface PackProgress {
    fun status(text: String)

    fun progress(
        value: Long,
        range: Long,
    )

    fun cancelled(): Boolean
}

/** The core's shader packs. Each call blocks (the network, the files): never on the main or the core's thread. */
interface ShaderPackApi {
    fun getShaderPacks(): List<ShaderPackInfo>

    fun checkShaderPacks(progress: PackProgress): List<ShaderPackCheck>

    /** Installs [ids], each pack's dependency first. */
    fun installShaderPacks(
        ids: List<String>,
        progress: PackProgress,
    ): List<ShaderPackResult>

    /** Null when it's removed, else why it wasn't. */
    fun removeShaderPack(id: String): String?

    /** The pack's status with [latest] as its latest version (null: unknown). */
    fun describeShaderPack(
        id: String,
        latest: String?,
    ): ShaderPackStatus
}

/** [ShaderPackApi] through the core, whose answers come as flat string records. */
object NativeShaderPackApi : ShaderPackApi {
    override fun getShaderPacks(): List<ShaderPackInfo> =
        NativeLibrary.getShaderPacks().toList().chunked(8).map {
            ShaderPackInfo(
                it[0],
                it[1],
                it[2],
                it[3],
                it[4].ifEmpty { null },
                it[5].ifEmpty { null },
                it[6] == "1",
                it[7],
            )
        }

    override fun checkShaderPacks(progress: PackProgress): List<ShaderPackCheck> =
        NativeLibrary.checkShaderPacks(progress).toList().chunked(4).map {
            ShaderPackCheck(it[0], it[1].ifEmpty { null }, it[2], it[3])
        }

    override fun installShaderPacks(
        ids: List<String>,
        progress: PackProgress,
    ): List<ShaderPackResult> =
        NativeLibrary.installShaderPacks(ids.toTypedArray(), progress).toList().chunked(4).map {
            ShaderPackResult(it[0], it[1] == "1", it[2] == "1", it[3])
        }

    override fun removeShaderPack(id: String): String? = NativeLibrary.removeShaderPack(id)

    override fun describeShaderPack(
        id: String,
        latest: String?,
    ): ShaderPackStatus = NativeLibrary.describeShaderPack(id, latest).let { ShaderPackStatus(it[0], it[1]) }
}
