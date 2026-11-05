// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import io.github.ilyalissoboi.goosestation.settings.CoreSettings
import io.github.ilyalissoboi.goosestation.settings.Setting
import io.github.ilyalissoboi.goosestation.settings.SettingValues

/** settings.ini as `section/key` to its text, for the pages' screen tests. */
private class FileSettings(
    private val values: Map<String, String>,
) : CoreSettings {
    override fun getString(
        section: String,
        key: String,
        default: String,
    ) = values["$section/$key"] ?: default

    override fun getInt(
        section: String,
        key: String,
        default: Int,
    ) = values["$section/$key"]?.toIntOrNull() ?: default

    override fun getBool(
        section: String,
        key: String,
        default: Boolean,
    ) = values["$section/$key"]?.toBooleanStrictOrNull() ?: default

    override fun getFloat(
        section: String,
        key: String,
        default: Float,
    ) = values["$section/$key"]?.toFloatOrNull() ?: default

    override fun setString(
        section: String,
        key: String,
        value: String?,
    ) = error("read only")

    override fun setInt(
        section: String,
        key: String,
        value: Int,
    ) = error("read only")

    override fun setBool(
        section: String,
        key: String,
        value: Boolean,
    ) = error("read only")

    override fun setFloat(
        section: String,
        key: String,
        value: Float,
    ) = error("read only")
}

/** A page's [settings] as a settings.ini holding [file] (`"GPU/Renderer" to "Vulkan"`) would give them. */
fun pageValues(
    settings: List<Setting<*>>,
    vararg file: Pair<String, String>,
): SettingValues = SettingValues.read(FileSettings(file.toMap()), settings)
