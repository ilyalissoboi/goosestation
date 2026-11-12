// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ilyalissoboi.goosestation.settings.BoolSetting
import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.FloatSetting
import io.github.ilyalissoboi.goosestation.settings.IntSetting
import io.github.ilyalissoboi.goosestation.settings.NameSetting
import io.github.ilyalissoboi.goosestation.settings.NextStart
import io.github.ilyalissoboi.goosestation.settings.Setting
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.settings.offered
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.LocalGameContext
import io.github.ilyalissoboi.goosestation.ui.components.NumberRow
import io.github.ilyalissoboi.goosestation.ui.components.NumberScale
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.components.useGlobalLabel
import kotlin.math.roundToInt

// A settings page's rows, each bound to its setting in a page's values: a change comes as the whole values with that
// setting changed ([onChange]).
//
// On a game's page (the values are a game's, SettingValues.isGame), each row can use the global setting: a choice
// row's first choice is "Use global (<its value>)", an on/off row is a choice of that, On and Off, and a number row
// steps from the global value. A row the game sets is marked [SET_FOR_GAME]; one a running game only takes at its
// next start says so ([NextStart]).

/** What a row the game's file sets says, on a game's page. */
const val SET_FOR_GAME = "Set for this game"

/** The mark for a row of [settings] on a game's page: [SET_FOR_GAME] when the game sets any of them. */
fun gameMark(
    values: SettingValues,
    vararg settings: Setting<*>,
): String? = SET_FOR_GAME.takeIf { settings.any(values::isSet) }

/** [values] following the global setting again for each of [settings]. */
fun SettingValues.withGlobal(vararg settings: Setting<*>): SettingValues =
    settings.fold(this) { values, setting -> values.withGlobal(setting) }

/** [note], with what a running game's [settings] say when any of them only applies at the next start. */
@Composable
fun gameNote(
    settings: List<Setting<*>>,
    note: String?,
): String? {
    val running = LocalGameContext.current?.running == true
    if (!running || settings.none { it in NextStart.settings }) return note
    return listOfNotNull(note, NextStart.NOTE).joinToString(" ")
}

/** [note], with what a running game's [setting] says when it only applies at the next start. */
@Composable
fun gameNote(
    setting: Setting<*>,
    note: String?,
): String? = gameNote(listOf(setting), note)

/** A [SwitchRow] for [setting]; on a game's page, a choice of the global setting, On and Off. */
@Composable
fun GameSwitch(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    label: String,
    setting: BoolSetting,
    note: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!values.isGame) {
        return SwitchRow(
            label,
            values[setting],
            onToggle = { onChange(values.with(setting, it)) },
            modifier = modifier,
            note = note,
            enabled = enabled,
        )
    }
    GameChoices(
        values,
        onChange,
        label,
        setting,
        listOf(Choice(true, ON), Choice(false, OFF)),
        note = note,
        modifier = modifier,
        enabled = enabled,
    )
}

/** A [NumberRow] for [setting], on [scale]. */
@Composable
fun GameNumber(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    label: String,
    setting: IntSetting,
    scale: NumberScale,
    note: String? = null,
    modifier: Modifier = Modifier,
) = GameNumberRow(
    values,
    onChange,
    label,
    listOf(setting),
    scale,
    value = { it[setting] },
    write = { v, number -> v.with(setting, number) },
    note = note,
    modifier = modifier,
)

/**
 * A [NumberRow] for a float [setting], on [scale] in [units] to the setting's one: 100 for a percentage of a factor
 * or hundredths of a second, 10 for tenths. A value between the units shows rounded, and is written only once changed.
 */
@Composable
fun GameNumber(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    label: String,
    setting: FloatSetting,
    scale: NumberScale,
    units: Int,
    note: String? = null,
    modifier: Modifier = Modifier,
) = GameNumberRow(
    values,
    onChange,
    label,
    listOf(setting),
    scale,
    value = { (it[setting] * units).roundToInt() },
    write = { v, number -> v.with(setting, number / units.toFloat()) },
    note = note,
    modifier = modifier,
)

/**
 * A [CyclerRow] for [setting], of the core's [names] for its list, with [notes] by value; a value settings.ini has that
 * the core doesn't name is offered too, marked.
 */
@Composable
fun GameNames(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    names: CoreNames,
    label: String,
    setting: NameSetting,
    note: String? = null,
    modifier: Modifier = Modifier,
    notes: Map<String, String> = emptyMap(),
    enabled: Boolean = true,
) {
    val choices = names[setting.list].orEmpty().map { it.withNote(notes[it.value]) }
    // A game's own value and the global one are each offered, as themselves, when the core doesn't name them.
    val shown =
        if (values.isGame) {
            offered(offered(choices, values.global(setting)), values[setting])
        } else {
            offered(choices, values[setting])
        }
    GameChoices(values, onChange, label, setting, shown, note = note, modifier = modifier, enabled = enabled)
}

/**
 * A [CyclerRow] for [setting], of [choices]. On a game's page its first choice is "Use global (<the global value's
 * label>)", selected while the game doesn't set it.
 */
@Composable
fun <T> GameChoices(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    label: String,
    setting: Setting<T>,
    choices: List<Choice<T>>,
    note: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = GameChoiceRow(
    values,
    onChange,
    label,
    listOf(setting),
    choices,
    value = { it[setting] },
    write = { v, picked -> v.with(setting, picked) },
    note = note,
    modifier = modifier,
    enabled = enabled,
)

/**
 * A [CyclerRow] whose value is made of several [settings] ([value] reads it, [write] writes it), of [choices]. On a
 * game's page it uses the global setting as a whole: its first choice is "Use global (<the value they give>)",
 * selected while the game sets none of them, and it's marked when the game sets any.
 */
@Composable
fun <T> GameGroupChoices(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    label: String,
    settings: List<Setting<*>>,
    choices: List<Choice<T>>,
    value: (SettingValues) -> T,
    write: (SettingValues, T) -> SettingValues,
    note: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = GameChoiceRow(values, onChange, label, settings, choices, value, write, note, modifier, enabled)

/**
 * A [NumberRow] whose number is made of several [settings] ([value] reads it, [write] writes it), on [scale]. On a
 * game's page it uses the global setting as a whole, as [GameGroupChoices] does.
 */
@Composable
fun GameGroupNumber(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    label: String,
    settings: List<Setting<*>>,
    scale: NumberScale,
    value: (SettingValues) -> Int,
    write: (SettingValues, Int) -> SettingValues,
    note: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = GameNumberRow(values, onChange, label, settings, scale, value, write, note, modifier, enabled)

// Every choice row above: on a game's page, "Use global" first, the mark, and the next-start note, over all [settings].
@Composable
private fun <T> GameChoiceRow(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    label: String,
    settings: List<Setting<*>>,
    choices: List<Choice<T>>,
    value: (SettingValues) -> T,
    write: (SettingValues, T) -> SettingValues,
    note: String?,
    modifier: Modifier,
    enabled: Boolean,
) {
    if (!values.isGame) {
        return CyclerRow(
            label,
            choices,
            value(values),
            onSelect = { onChange(write(values, it)) },
            modifier = modifier,
            note = note,
            enabled = enabled,
        )
    }
    val all = settings.toTypedArray()
    val global = value(values.withGlobal(*all))
    val globalLabel = choices.firstOrNull { it.value == global }?.label ?: global.toString()
    CyclerRow<T?>(
        label,
        listOf(Choice<T?>(null, useGlobalLabel(globalLabel))) + choices.map { Choice<T?>(it.value, it.label, it.note) },
        if (settings.any(values::isSet)) value(values) else null,
        onSelect = { picked ->
            @Suppress("UNCHECKED_CAST")
            onChange(if (picked == null) values.withGlobal(*all) else write(values, picked as T))
        },
        modifier = modifier,
        note = gameNote(settings, note),
        enabled = enabled,
        mark = gameMark(values, *all),
    )
}

// Every number row above: on a game's page it steps from the global value, over all [settings].
@Composable
private fun GameNumberRow(
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    label: String,
    settings: List<Setting<*>>,
    scale: NumberScale,
    value: (SettingValues) -> Int,
    write: (SettingValues, Int) -> SettingValues,
    note: String?,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    val all = settings.toTypedArray()
    NumberRow(
        label,
        value(values),
        scale,
        onChange = { onChange(write(values, it)) },
        modifier = modifier,
        note = gameNote(settings, note),
        enabled = enabled,
        mark = gameMark(values, *all),
        global = if (values.isGame) value(values.withGlobal(*all)) else null,
        followsGlobal = values.isGame && settings.none(values::isSet),
        onUseGlobal = if (values.isGame) ({ onChange(values.withGlobal(*all)) }) else null,
    )
}

private const val ON = "On"
private const val OFF = "Off"

private fun Choice<String>.withNote(note: String?) = if (note == null) this else copy(note = note)
