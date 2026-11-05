// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

/** A setting in settings.ini: its [section] and [key], as desktop names them, and the core's [default]. */
sealed interface Setting<T> {
    val section: String
    val key: String
    val default: T

    /** The value in [s], or [default] when it isn't set. */
    fun read(s: CoreSettings): T

    fun write(
        s: CoreSettings,
        value: T,
    )
}

data class BoolSetting(
    override val section: String,
    override val key: String,
    override val default: Boolean,
) : Setting<Boolean> {
    override fun read(s: CoreSettings) = s.getBool(section, key, default)

    override fun write(
        s: CoreSettings,
        value: Boolean,
    ) = s.setBool(section, key, value)
}

data class IntSetting(
    override val section: String,
    override val key: String,
    override val default: Int,
) : Setting<Int> {
    override fun read(s: CoreSettings) = s.getInt(section, key, default)

    override fun write(
        s: CoreSettings,
        value: Int,
    ) = s.setInt(section, key, value)
}

data class FloatSetting(
    override val section: String,
    override val key: String,
    override val default: Float,
) : Setting<Float> {
    override fun read(s: CoreSettings) = s.getFloat(section, key, default)

    override fun write(
        s: CoreSettings,
        value: Float,
    ) = s.setFloat(section, key, value)
}

/** A setting whose value is the name of one of the core's [list]'s choices, as settings.ini has it. */
data class NameSetting(
    override val section: String,
    override val key: String,
    override val default: String,
    val list: NameList,
) : Setting<String> {
    override fun read(s: CoreSettings) = s.getString(section, key, default)

    override fun write(
        s: CoreSettings,
        value: String,
    ) = s.setString(section, key, value)
}
