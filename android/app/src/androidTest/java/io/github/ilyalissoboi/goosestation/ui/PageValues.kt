// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import io.github.ilyalissoboi.goosestation.core.TypeSetting
import io.github.ilyalissoboi.goosestation.settings.CoreSettings
import io.github.ilyalissoboi.goosestation.settings.MacroSettings
import io.github.ilyalissoboi.goosestation.settings.PortSettings
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

/** A type's settings as the core describes NeGcon's: sixteen floats, the longest Port page. */
val NEGCON_SETTINGS: List<TypeSetting> =
    listOf("Steering", "I", "II", "L").flatMap { input ->
        listOf("Deadzone", "Saturation", "Linearity", "Scaling").map { what ->
            TypeSetting(
                TypeSetting.Kind.Float,
                "$input$what",
                "$input Button $what",
                "How the $input input's $what shapes what the game sees, from the controller's own range.",
                "0.00",
                "0.00",
                "2.00",
                "0.01",
                "%.0f%%",
                100f,
                emptyList(),
            )
        }
    }

/** A port's page's values for [settings] and its macros, as a settings.ini holding [file] would give them. */
fun portPageValues(
    settings: List<TypeSetting>,
    vararg file: Pair<String, String>,
) = pageValues(PortSettings.settings(0, settings) + (1..MacroSettings.COUNT).map { MacroSettings.binds(0, it) }, *file)
