// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

/** [CoreSettings] in a map keyed by `section/key`, each value as settings.ini would hold it. */
class MemoryCoreSettings : CoreSettings {
    val values = mutableMapOf<String, String>()

    override fun getString(
        section: String,
        key: String,
        default: String,
    ) = values["$section/$key"] ?: default

    override fun setString(
        section: String,
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove("$section/$key") else values["$section/$key"] = value
    }

    override fun getInt(
        section: String,
        key: String,
        default: Int,
    ) = values["$section/$key"]?.toIntOrNull() ?: default

    override fun setInt(
        section: String,
        key: String,
        value: Int,
    ) {
        values["$section/$key"] = value.toString()
    }

    override fun getBool(
        section: String,
        key: String,
        default: Boolean,
    ) = values["$section/$key"]?.toBooleanStrictOrNull() ?: default

    override fun setBool(
        section: String,
        key: String,
        value: Boolean,
    ) {
        values["$section/$key"] = value.toString()
    }
}
