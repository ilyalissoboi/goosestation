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
import io.github.ilyalissoboi.goosestation.settings.MacroSettings
import io.github.ilyalissoboi.goosestation.ui.components.FocusHolderLine
import io.github.ilyalissoboi.goosestation.ui.components.NumberRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow

/**
 * The macro [state]'s view shows ([ControllersView.Macro]), titled with it and its port: the controller it shows among
 * those on the port, and its trigger (its macro triggers' entry for this macro, once they're its own), captured,
 * cleared and undone as a binding is; then, once its settings are read, a switch for each of the type's inputs it
 * presses, how often a held macro repeats them, and whether a press toggles it. Each goes to [actions]. With no
 * controller on the port, a line says how to put one there, and the rest stays. The focus starts on the first row. B
 * or Back go back ([onBack]); Back during a capture cancels it.
 */
@Composable
fun MacroScreen(
    state: ControllersState,
    actions: ControllersActions,
    onBack: () -> Unit,
) {
    val view = state.view as? ControllersView.Macro ?: return
    val (pad, macro) = view
    val port = state.portLabel(pad)
    val first = remember { FocusRequester() }
    val controller = state.controller
    val key = "Macro$macro"
    val values = state.macroValues
    val inputs = state.macroInputs

    BindingsPage("Macro $macro · $port", state, actions, onBack, focus = { first }) {
        SettingsSection("Trigger")
        if (controller == null) {
            FocusHolderLine(
                "No controller is on $port. Pick $port for one on the Controllers page.",
                requester = first,
            )
        } else {
            ControllerPicker(state.covered, state.labels, controller, actions::select, Modifier.focusRequester(first))
            val trigger = state.macros.orEmpty().firstOrNull { it.name == key }
            if (state.macros != null && state.bindingsFor == controller.descriptor && trigger != null) {
                CaptureBindingRow(
                    trigger.copy(displayName = "Trigger"),
                    ask = "Press the button for ${trigger.displayName}",
                    state = state,
                    actions = actions,
                    name = key,
                )
            } else {
                Line("Reading ${state.label(controller)}'s trigger…")
            }
        }
        if (values == null) return@BindingsPage

        SettingsSection("Presses")
        val binds = MacroSettings.binds(pad, macro)
        val pressed = MacroSettings.buttons(values[binds])
        inputs.forEach { input ->
            SwitchRow(
                input.displayName,
                input.name in pressed,
                onToggle = { on ->
                    val buttons = if (on) pressed + input.name else pressed - input.name
                    actions.changeMacro(values.with(binds, MacroSettings.binds(buttons, inputs)))
                },
            )
        }

        SettingsSection("Repeat")
        val frequency = MacroSettings.frequency(pad, macro)
        NumberRow(
            "Repeats",
            values[frequency],
            MacroSettings.FREQUENCY,
            onChange = { actions.changeMacro(values.with(frequency, it)) },
            note = "While the trigger is held, how often the presses let go and press again.",
        )
        val toggle = MacroSettings.toggle(pad, macro)
        SwitchRow(
            "Press to toggle",
            values[toggle],
            onToggle = { actions.changeMacro(values.with(toggle, it)) },
            note = "A press turns the macro on, the next turns it off.",
        )
    }
}
