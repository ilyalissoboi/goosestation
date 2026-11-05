// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

/**
 * A page's settings and their values, as read from settings.ini, then as the page changed them, one at a time. Kept
 * in the page's order. Equal when every value is.
 */
class SettingValues private constructor(
    private val values: LinkedHashMap<Setting<*>, Any?>,
) {
    /** [setting]'s value; it must be one of the settings these were read with. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(setting: Setting<T>): T {
        require(setting in values) { "${setting.section}/${setting.key} wasn't read with this page" }
        return values[setting] as T
    }

    /** These values, with [setting]'s [value] instead. */
    fun <T> with(
        setting: Setting<T>,
        value: T,
    ): SettingValues {
        require(setting in values) { "${setting.section}/${setting.key} wasn't read with this page" }
        return SettingValues(LinkedHashMap(values).apply { put(setting, value) })
    }

    /** The settings whose values differ from [old]'s, in the page's order. */
    fun changed(old: SettingValues): List<Setting<*>> = values.keys.filter { values[it] != old.values[it] }

    /** Writes to [s] the settings whose values differ from [old]'s, and nothing else. */
    fun save(
        s: CoreSettings,
        old: SettingValues,
    ) {
        for (setting in changed(old)) write(s, setting)
    }

    private fun <T> write(
        s: CoreSettings,
        setting: Setting<T>,
    ) = setting.write(s, get(setting))

    override fun equals(other: Any?) = other is SettingValues && values == other.values

    override fun hashCode() = values.hashCode()

    override fun toString() = values.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "${k.key}=$v" }

    companion object {
        /** [settings]' values in [s], each its default when it isn't set. */
        fun read(
            s: CoreSettings,
            settings: Collection<Setting<*>>,
        ) = SettingValues(LinkedHashMap<Setting<*>, Any?>().apply { for (it in settings) put(it, it.read(s)) })
    }
}
