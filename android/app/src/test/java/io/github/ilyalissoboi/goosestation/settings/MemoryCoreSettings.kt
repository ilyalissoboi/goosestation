// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

/** [CoreSettings] in a map keyed by `section/key`, each value as settings.ini would hold it. */
class MemoryCoreSettings : CoreSettings {
    val values = mutableMapOf<String, String>()

    /** Runs once, as the next value is read, as if the user acted while a page was being read. */
    var duringRead: () -> Unit = {}

    private fun read(
        section: String,
        key: String,
    ): String? {
        val hook = duringRead
        duringRead = {}
        hook()
        return values["$section/$key"]
    }

    override fun getString(
        section: String,
        key: String,
        default: String,
    ) = read(section, key) ?: default

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
    ) = read(section, key)?.toIntOrNull() ?: default

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
    ) = read(section, key)?.toBooleanStrictOrNull() ?: default

    override fun setBool(
        section: String,
        key: String,
        value: Boolean,
    ) {
        values["$section/$key"] = value.toString()
    }

    override fun getFloat(
        section: String,
        key: String,
        default: Float,
    ) = read(section, key)?.toFloatOrNull() ?: default

    override fun setFloat(
        section: String,
        key: String,
        value: Float,
    ) {
        values["$section/$key"] = value.toString()
    }
}
