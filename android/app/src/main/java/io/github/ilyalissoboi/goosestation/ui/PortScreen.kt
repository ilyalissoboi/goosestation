// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.ilyalissoboi.goosestation.app.ControllersActions
import io.github.ilyalissoboi.goosestation.app.ControllersState
import io.github.ilyalissoboi.goosestation.app.ControllersView
import io.github.ilyalissoboi.goosestation.core.TypeSetting
import io.github.ilyalissoboi.goosestation.settings.BoolSetting
import io.github.ilyalissoboi.goosestation.settings.FloatSetting
import io.github.ilyalissoboi.goosestation.settings.IntSetting
import io.github.ilyalissoboi.goosestation.settings.MacroSettings
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.settings.offered
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.FocusHolderLine
import io.github.ilyalissoboi.goosestation.ui.components.NavRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsLoadingPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection

/**
 * The page of the port [state]'s view shows ([ControllersView.Port]), titled with its name: the bindings of the
 * controller it shows among those on the port, one row each, once they're its own ([ControllersState.bindingsFor]);
 * until then, a line says they're being read, and until the port's bindings are first read, the page says it's reading.
 * With more than one controller, a row picks which; with none, a line says how to put one on the port. A row's A or
 * Enter starts a capture for it: while it's captured, it asks for an input, with the seconds left. Right reaches the
 * row's Clear, or its Undo after a capture, until the focus leaves the row. Reset to defaults ends the page. Each goes
 * to [actions]. The focus starts on the page's first row, never on a binding, so an A pressed as the page opens
 * captures nothing; it moves only when nothing in the page has it, never as a controller comes or goes; back from a
 * macro, it goes to that macro's row. B or Back go back ([onBack]); Back during a capture cancels it. The inputs show as
 * their PlayStation glyphs for a type that has them ([ControllersState.glyphs]), else as words.
 */
@Composable
fun PortScreen(
    state: ControllersState,
    actions: ControllersActions,
    onBack: () -> Unit,
) {
    val pad = (state.view as? ControllersView.Port)?.pad ?: return
    val title = state.portLabel(pad)
    val bindings = state.bindings
    if (bindings == null) {
        SettingsLoadingPage(title, onBack = onBack)
        return
    }
    val type = state.portTypes[pad]
    val values = state.portValues
    val first = remember { FocusRequester() }
    val macroRows = remember { List(MacroSettings.COUNT) { FocusRequester() } }
    val controller = state.controller

    // On opening, and whenever the row with the focus goes, as when the controller's name becomes the Controller row;
    // back from a macro, its row.
    BindingsPage(title, state, actions, onBack, focus = {
        val macroRow = state.macroFocus?.let { macroRows.getOrNull(it - 1) }
        if (macroRow != null && values != null) macroRow else first
    }) {
        if (type != null) {
            CyclerRow(
                "Controller type",
                offered(state.typeNames, type),
                type,
                onSelect = { actions.setType(pad, it) },
                modifier = Modifier.focusRequester(first),
            )
            // Not Connected: nothing to set, bind or press.
            if (type == NOT_CONNECTED) return@BindingsPage
        }
        if (values != null && state.typeSettings.isNotEmpty()) {
            SettingsSection("Settings")
            state.typeSettings.forEach { TypeSettingRow(pad, it, values, actions::changePort) }
        }
        if (type != null) SettingsSection("Bindings")
        val firstHere = if (type == null) Modifier.focusRequester(first) else Modifier
        if (controller == null) {
            // It holds the focus only as the first row, so B and Back reach the page; below the type, no stop.
            FocusHolderLine(
                "No controller is on $title. Pick $title for one on the Controllers page.",
                requester = if (type == null) first else null,
                enabled = type == null,
            )
        } else {
            // It holds the focus on opening, with nothing to activate for one controller.
            ControllerPicker(
                state.covered,
                state.labels,
                controller,
                actions::select,
                firstHere,
                nameFocusable = type == null,
            )
            Line("Each controller on $title keeps its own bindings.")
            if (state.bindingsFor != controller.descriptor) {
                Line("Reading ${state.label(controller)}'s bindings…")
            } else {
                bindings.forEachIndexed { i, binding ->
                    // A type a game's own file gives the port: its inputs, after the port's own, under its name.
                    if (binding.category.isNotEmpty() && binding.category != bindings.getOrNull(i - 1)?.category) {
                        SettingsSection("${binding.category} (a game's)")
                    }
                    CaptureBindingRow(
                        binding,
                        ask = "Press the new button for ${binding.displayName}",
                        state = state,
                        actions = actions,
                        glyph = state.glyphs,
                    )
                }
            }
        }
        if (values != null) {
            SettingsSection("Macros")
            for (macro in 1..MacroSettings.COUNT) {
                NavRow(
                    "Macro $macro",
                    values[MacroSettings.binds(pad, macro)].ifEmpty { "Not set" },
                    onClick = { actions.openMacro(pad, macro) },
                    modifier = Modifier.focusRequester(macroRows[macro - 1]),
                )
            }
        }
        if (controller != null && state.bindingsFor == controller.descriptor) {
            ActionButton("Reset to defaults", onClick = actions::reset, kind = ButtonKind.Danger)
        }
    }
}

/** The controller type that's no controller: `ControllerType::None`'s name. */
private const val NOT_CONNECTED = "None"

/** One of a type's settings, as its kind shows it, its description as its note; on a game's page, a game's row. */
@Composable
internal fun TypeSettingRow(
    pad: Int,
    s: TypeSetting,
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    modifier: Modifier = Modifier,
) {
    val note = s.description.ifEmpty { null }
    when (val setting = PortSettings.setting(pad, s)) {
        is BoolSetting -> GameSwitch(values, onChange, s.label, setting, note, modifier)
        is IntSetting ->
            if (s.kind == TypeSetting.Kind.IntList) {
                GameChoices(
                    values,
                    onChange,
                    s.label,
                    setting,
                    offered(PortSettings.choices(s), values[setting]) { "$it" },
                    note,
                    modifier,
                )
            } else {
                PortSettings.scale(s)?.let { scale ->
                    GameNumber(values, onChange, s.label, setting, scale, note, modifier)
                }
            }
        is FloatSetting ->
            PortSettings.scale(s)?.let { scale ->
                GameGroupNumber(
                    values,
                    onChange,
                    s.label,
                    listOf(setting),
                    scale,
                    value = { PortSettings.units(s, it[setting]) },
                    write = { v, units -> v.with(setting, PortSettings.value(s, units)) },
                    note = note,
                    modifier = modifier,
                )
            }
        // None of the types the page offers has a text setting.
        else -> {}
    }
}
