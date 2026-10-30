// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.core.NativeLibrary

/**
 * The core's settings.ini, by desktop's section and key names. Each set is saved straight away; nothing is applied
 * until the core is told to. The getters give the default when the key isn't set.
 */
interface CoreSettings {
    fun getString(
        section: String,
        key: String,
        default: String,
    ): String

    /** Sets [key] to [value], or deletes it if [value] is null. */
    fun setString(
        section: String,
        key: String,
        value: String?,
    )

    fun getInt(
        section: String,
        key: String,
        default: Int,
    ): Int

    fun setInt(
        section: String,
        key: String,
        value: Int,
    )

    fun getBool(
        section: String,
        key: String,
        default: Boolean,
    ): Boolean

    fun setBool(
        section: String,
        key: String,
        value: Boolean,
    )
}

/** [CoreSettings] on [NativeLibrary]: they do I/O, so call them off the main thread. */
object NativeCoreSettings : CoreSettings {
    override fun getString(
        section: String,
        key: String,
        default: String,
    ) = NativeLibrary.getStringSetting(section, key, default)

    override fun setString(
        section: String,
        key: String,
        value: String?,
    ) = NativeLibrary.setStringSetting(section, key, value)

    override fun getInt(
        section: String,
        key: String,
        default: Int,
    ) = NativeLibrary.getIntSetting(section, key, default)

    override fun setInt(
        section: String,
        key: String,
        value: Int,
    ) = NativeLibrary.setIntSetting(section, key, value)

    override fun getBool(
        section: String,
        key: String,
        default: Boolean,
    ) = NativeLibrary.getBoolSetting(section, key, default)

    override fun setBool(
        section: String,
        key: String,
        value: Boolean,
    ) = NativeLibrary.setBoolSetting(section, key, value)
}
