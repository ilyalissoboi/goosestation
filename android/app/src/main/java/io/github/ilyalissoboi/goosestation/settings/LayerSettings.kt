// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.core.NativeLibrary

/** Settings that sit over settings.ini, such as a game's own: they can say whether they set a key, and drop it. */
interface LayerSettings : CoreSettings {
    fun contains(
        section: String,
        key: String,
    ): Boolean

    fun delete(
        section: String,
        key: String,
    )
}

/**
 * The game's own settings, `gamesettings/<key>.ini`, by the game's [key], as the core finds them: on [NativeLibrary],
 * so call them off the main thread.
 */
class GameCoreSettings(
    val key: String,
) : LayerSettings {
    override fun contains(
        section: String,
        key: String,
    ) = NativeLibrary.hasGameSetting(this.key, section, key)

    override fun delete(
        section: String,
        key: String,
    ) = NativeLibrary.deleteGameSetting(this.key, section, key)

    override fun getString(
        section: String,
        key: String,
        default: String,
    ) = NativeLibrary.getGameStringSetting(this.key, section, key, default)

    override fun setString(
        section: String,
        key: String,
        value: String?,
    ) = NativeLibrary.setGameStringSetting(this.key, section, key, value)

    override fun getInt(
        section: String,
        key: String,
        default: Int,
    ) = NativeLibrary.getGameIntSetting(this.key, section, key, default)

    override fun setInt(
        section: String,
        key: String,
        value: Int,
    ) = NativeLibrary.setGameIntSetting(this.key, section, key, value)

    override fun getBool(
        section: String,
        key: String,
        default: Boolean,
    ) = NativeLibrary.getGameBoolSetting(this.key, section, key, default)

    override fun setBool(
        section: String,
        key: String,
        value: Boolean,
    ) = NativeLibrary.setGameBoolSetting(this.key, section, key, value)

    override fun getFloat(
        section: String,
        key: String,
        default: Float,
    ) = NativeLibrary.getGameFloatSetting(this.key, section, key, default)

    override fun setFloat(
        section: String,
        key: String,
        value: Float,
    ) = NativeLibrary.setGameFloatSetting(this.key, section, key, value)
}
