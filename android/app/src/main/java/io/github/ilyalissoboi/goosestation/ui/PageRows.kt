// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ilyalissoboi.goosestation.settings.BoolSetting
import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.IntSetting
import io.github.ilyalissoboi.goosestation.settings.NameSetting
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.settings.offered
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.NumberRow
import io.github.ilyalissoboi.goosestation.ui.components.NumberScale
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow

// A settings page's rows, each bound to its setting in a page's values: a change comes as the whole values with that
// setting changed ([onChange]).

/** A [SwitchRow] for [setting]. */
@Composable
fun Switch(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    label: String,
    setting: BoolSetting,
    note: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = SwitchRow(
    label,
    values[setting],
    onToggle = { onChange(values.with(setting, it)) },
    modifier = modifier,
    note = note,
    enabled = enabled,
)

/** A [NumberRow] for [setting], on [scale]. */
@Composable
fun Number(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    label: String,
    setting: IntSetting,
    scale: NumberScale,
    note: String? = null,
    modifier: Modifier = Modifier,
) = NumberRow(
    label,
    values[setting],
    scale,
    onChange = { onChange(values.with(setting, it)) },
    modifier = modifier,
    note = note,
)

/**
 * A [CyclerRow] for [setting], of the core's [names] for its list, with [notes] by value; a value settings.ini has that
 * the core doesn't name is offered too, marked.
 */
@Composable
fun Names(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    names: CoreNames,
    label: String,
    setting: NameSetting,
    note: String? = null,
    modifier: Modifier = Modifier,
    notes: Map<String, String> = emptyMap(),
) = CyclerRow(
    label,
    offered(names[setting.list].orEmpty().map { it.withNote(notes[it.value]) }, values[setting]),
    values[setting],
    onSelect = { onChange(values.with(setting, it)) },
    modifier = modifier,
    note = note,
)

private fun Choice<String>.withNote(note: String?) = if (note == null) this else copy(note = note)
